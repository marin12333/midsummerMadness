package com.madness.mqmremovemark.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.madness.mqmremovemark.common.BusinessException;
import com.madness.mqmremovemark.common.ErrorCode;
import com.madness.mqmremovemark.model.WeiboMediaVO;
import jakarta.servlet.http.HttpServletResponse;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WeiboMediaService {

    private static final ObjectMapper mapper = new ObjectMapper();

    /** 走 m.weibo.cn 的 statuses/show JSON 接口，移动 UA 最稳；桌面 UA 会被导去带反爬的 SPA 壳 */
    private static final String DEFAULT_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) "
                    + "AppleWebKit/605.1.15 (KHTML, like Gecko) "
                    + "Version/16.0 Mobile/15E148 Safari/604.1";

    // 进程内游客 Cookie 容器：m.weibo.cn 的 ajax 接口即使对公开微博也要先持有 _T_WM 等游客 cookie，
    // 否则一律返回 ok:0。用 CookieJar 自动收集首次访问 H5 页时下发的 Set-Cookie，后续请求自动带上。
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(new InMemoryCookieJar())
            .build();

    @Value("${weibo.user-agent:" + DEFAULT_UA + "}")
    private String userAgent;

    /**
     * 部分微博（尤其需要登录可见 / 风控较严的）必须带 Cookie 才能拿到完整 data；
     * 公开微博一般不需要，留空即可。从浏览器登录 m.weibo.cn 后复制 SUB 等字段。
     */
    @Value("${weibo.cookie:}")
    private String cookie;

    /** 游客 cookie 已就绪标记：握手一次即可，cookie 有效期很长，不必每个请求都重跑 */
    private volatile boolean visitorReady = false;

    /** 媒体代理允许的 CDN 域名后缀白名单，防 SSRF：只放行微博自家的图片 / 视频 CDN */
    private static final Set<String> ALLOWED_MEDIA_HOST_SUFFIX =
            Set.of("sinaimg.cn", "weibocdn.com", "video.weibo.com");

    public WeiboMediaVO extractMedia(String rawShareText) {
        String shareUrl = extractUrl(rawShareText);
        if (shareUrl == null) {
            throw new BusinessException(ErrorCode.URL_NOT_FOUND);
        }
        seedConfiguredCookie();
        try {
            // video.weibo.com / h5.video.weibo.com 的「视频卡片」链接，fid 形如 1034:数字，
            // 它不是普通博文 mid，statuses/show 会回「该微博不存在」，得走视频组件接口
            String fid = extractVideoFid(shareUrl);
            // 普通博文：m.weibo.cn/status、weibo.com/{uid}/{bid} 等
            String id = fid == null ? extractStatusId(shareUrl) : null;
            // 两种都没提到 → 多半是 t.cn 短链，跑一遍 redirect 拿真实地址再分别试
            if (fid == null && id == null) {
                String resolvedUrl = getRealUrl(shareUrl);
                fid = extractVideoFid(resolvedUrl);
                if (fid == null) id = extractStatusId(resolvedUrl);
            }

            if (fid != null) {
                return buildVideoVO(fetchVideoComponent(fid));
            }
            if (id == null) {
                throw new BusinessException(ErrorCode.DATA_PARSE_ERROR, "未提取到微博ID");
            }
            // 关键：m.weibo.cn 的 ajax 接口对公开微博也要求先持有访客系统签发的 SUB/_T_WM；
            // 没有它会返回 ok:0。跑一遍访客握手把 cookie 备齐（已备齐则直接复用）。
            ensureVisitorCookie(false);
            JsonNode data = fetchStatus(id);
            if (data == null) {
                // 可能是握手 cookie 过期了，强制重握一次再试
                ensureVisitorCookie(true);
                data = fetchStatus(id);
            }
            if (data == null) {
                throw new BusinessException(ErrorCode.NOTE_EMPTY,
                        "微博数据为空或不可见（可能已被删除 / 需登录 Cookie）");
            }
            return buildVO(data);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.NETWORK_ERROR, "网络请求异常: " + e.getMessage());
        }
    }

    /** 调 statuses/show 并返回 data 节点；ok!=1（含 errno 风控）时返回 null 交由上层决定是否重握手 */
    private JsonNode fetchStatus(String id) throws IOException {
        // statuses/show 接受数字 mid 或 base62 的 bid，两种都能直接当 id 传
        String apiUrl = "https://m.weibo.cn/statuses/show?id=" + id;
        // Referer 必须指向该微博的详情页，否则接口回 errno 100015「不合法的请求」
        String referer = "https://m.weibo.cn/detail/" + id;
        String body = fetchJson(apiUrl, referer);
        JsonNode root = mapper.readTree(body);
        if (root.path("ok").asInt(0) != 1 || root.path("data").isMissingNode()) {
            return null;
        }
        return root.path("data");
    }

    /**
     * video.weibo.com 视频卡片走 h5 的播放组件接口：
     *   POST https://h5.video.weibo.com/api/component?page=/show/{fid}
     *   body: data={"Component_Play_Playinfo":{"oid":"{fid}"}}
     * 返回 data.Component_Play_Playinfo，里头 urls 是各清晰度的 mp4 直链（无水印）。
     * 这个接口不需要访客握手，公开视频直接可取。
     */
    private JsonNode fetchVideoComponent(String fid) throws IOException {
        String page = "/show/" + fid;
        String url = "https://h5.video.weibo.com/api/component?page="
                + java.net.URLEncoder.encode(page, "UTF-8");
        String payload = "data={\"Component_Play_Playinfo\":{\"oid\":\"" + fid + "\"}}";
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Referer", "https://h5.video.weibo.com" + page)
                .header("Accept", "application/json, text/plain, */*")
                .post(okhttp3.RequestBody.create(
                        payload, okhttp3.MediaType.parse("application/x-www-form-urlencoded")))
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("请求失败: HTTP " + response.code());
            }
            String body = response.body() == null ? "" : response.body().string();
            JsonNode root = mapper.readTree(body);
            JsonNode info = root.path("data").path("Component_Play_Playinfo");
            if (!"100000".equals(root.path("code").asText("")) || info.isMissingNode()) {
                throw new BusinessException(ErrorCode.NOTE_EMPTY,
                        "视频数据为空或不可见: " + root.path("msg").asText(""));
            }
            return info;
        }
    }

    private WeiboMediaVO buildVideoVO(JsonNode info) {
        String title = firstNonEmpty(info.path("title").asText(""), info.path("text").asText(""));
        String author = firstNonEmpty(
                info.path("author").asText(""), info.path("nickname").asText(""));
        List<String> videos = new ArrayList<>();
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        // urls 是 {"高清 720P": "//...mp4", "标清 480P": "//...mp4"}，收集所有清晰度后只取最高那条
        JsonNode urls = info.path("urls");
        if (urls.isObject()) {
            urls.forEach(u -> addIfPresent(normalizeUrl(u.asText("")), ordered));
        }
        String best = pickHighest(ordered);
        if (best != null) videos.add(best);
        String cover = normalizeUrl(info.path("cover_image").asText(""));
        return new WeiboMediaVO(title, author, "video",
                new ArrayList<>(), new ArrayList<>(), videos, cover.isEmpty() ? null : cover);
    }

    /** 微博不少 URL 是协议相对（//开头），补成 https */
    private String normalizeUrl(String url) {
        if (url == null || url.isEmpty()) return "";
        if (url.startsWith("//")) return "https:" + url;
        return url;
    }

    private WeiboMediaVO buildVO(JsonNode data) {
        // text 是带 HTML 的正文（含 @、话题、表情 <img>），用 Jsoup 抽纯文本当标题
        String title = Jsoup.parse(data.path("text").asText("")).text();
        String author = data.path("user").path("screen_name").asText("");

        List<String> images = new ArrayList<>();
        List<String> livePhotos = new ArrayList<>();
        List<String> videos = new ArrayList<>();
        String cover = null;

        // 1) 视频：page_info.type == video 时，media_info / urls 里有多档 mp4 直链
        JsonNode pageInfo = data.path("page_info");
        boolean isVideo = "video".equalsIgnoreCase(pageInfo.path("type").asText(""))
                || !pageInfo.path("media_info").isMissingNode();
        if (isVideo) {
            collectVideoUrls(pageInfo, videos);
            cover = firstNonEmpty(
                    pageInfo.path("page_pic").path("url").asText(""),
                    pageInfo.path("page_pic").asText(""));
        }

        // 2) 图片 / 实况图：pics 数组里每项有 large.url，实况图额外带 videoSrc
        JsonNode pics = data.path("pics");
        if (pics.isArray() && pics.size() > 0) {
            for (JsonNode pic : pics) {
                String imgUrl = firstNonEmpty(
                        pic.path("large").path("url").asText(""),
                        pic.path("url").asText(""));
                if (!imgUrl.isEmpty()) {
                    // 关键：large/mw2000 这些尺寸微博会烧上「微博 @昵称」水印，
                    // 只有 oslarge 尺寸是无水印原图，统一改走 oslarge
                    images.add(toNoWatermarkImage(imgUrl));
                }
                // 实况图：每张图自带一段小视频，字段名在不同版本里有 videoSrc / video 两种
                String live = firstNonEmpty(
                        pic.path("videoSrc").asText(""),
                        pic.path("video").asText(""));
                if (!live.isEmpty()) {
                    livePhotos.add(live);
                }
            }
        }
        // 兜底：老结构把实况视频塞在顶层 pic_video（"picId:url,picId:url" 形式）
        if (livePhotos.isEmpty()) {
            parsePicVideo(data.path("pic_video").asText(""), livePhotos);
        }

        if (cover == null && !images.isEmpty()) {
            cover = images.get(0);
        }

        // 有图就算图集（含实况图），否则才算视频帖
        String type = images.isEmpty() ? "video" : "image";
        return new WeiboMediaVO(title, author, type, images, livePhotos, videos, cover);
    }

    /**
     * 微博图片去水印：URL 形如 https://wx4.sinaimg.cn/{尺寸}/{pid}.jpg，
     * large / mw2000 / original / woriginal / bmiddle 这些尺寸都会被微博烧上「微博 @昵称」水印，
     * 实测只有 oslarge 尺寸是无水印原图，这里把尺寸目录段统一替换成 oslarge。
     */
    private String toNoWatermarkImage(String url) {
        if (url == null || url.isEmpty()) return url;
        return url.replaceFirst("(://[^/]*sinaimg\\.cn/)[^/]+/", "$1oslarge/");
    }

    private void collectVideoUrls(JsonNode pageInfo, List<String> videos) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        JsonNode mediaInfo = pageInfo.path("media_info");
        for (String key : new String[]{
                "mp4_1080p_mp4", "mp4_720p_mp4", "mp4_hd_url", "hd_url",
                "stream_url_hd", "mp4_sd_url", "stream_url", "h5_url"
        }) {
            addIfPresent(mediaInfo.path(key).asText(""), ordered);
        }
        // urls 是个 {清晰度: 直链} 的 map，补充进来
        JsonNode urls = pageInfo.path("urls");
        if (urls.isObject()) {
            urls.forEach(u -> addIfPresent(u.asText(""), ordered));
        }
        // 收集到的多档清晰度里只取最高那条
        String best = pickHighest(ordered);
        if (best != null) videos.add(best);
    }

    /**
     * 从一组同一视频的不同清晰度直链里挑分辨率最高的一条。
     * 优先按 URL 里的 template=宽x高 算像素面积；没有 template 时按 label/关键字兜底打分。
     */
    private String pickHighest(java.util.Collection<String> urls) {
        String best = null;
        long bestScore = -1;
        for (String u : urls) {
            long s = qualityScore(u);
            if (s > bestScore) {
                bestScore = s;
                best = u;
            }
        }
        return best;
    }

    private long qualityScore(String url) {
        Matcher m = Pattern.compile("template=(\\d+)x(\\d+)").matcher(url);
        if (m.find()) {
            return (long) Integer.parseInt(m.group(1)) * Integer.parseInt(m.group(2));
        }
        String s = url.toLowerCase();
        if (s.contains("2160") || s.contains("4k")) return 3840L * 2160;
        if (s.contains("1440")) return 2560L * 1440;
        if (s.contains("1080")) return 1920L * 1080;
        if (s.contains("720")) return 1280L * 720;
        if (s.contains("hd")) return 854L * 480;
        if (s.contains("sd") || s.contains("ld")) return 640L * 360;
        return 0;
    }

    /** pic_video 形如 "pid1:https://...mp4,pid2:https://...mp4"，url 自身含冒号，按首个 http 切 */
    private void parsePicVideo(String picVideo, List<String> out) {
        if (picVideo == null || picVideo.isEmpty()) return;
        for (String part : picVideo.split(",")) {
            int idx = part.indexOf("http");
            if (idx >= 0) {
                String u = part.substring(idx).trim();
                if (!u.isEmpty()) out.add(u);
            }
        }
    }

    private void addIfPresent(String url, LinkedHashSet<String> set) {
        if (url != null && url.startsWith("http")) {
            set.add(url);
        }
    }

    private String firstNonEmpty(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isEmpty()) return c;
        }
        return "";
    }

    /** 把 application.yaml 里配置的登录 Cookie 字符串（"k1=v1; k2=v2"）拆开塞进 jar，与游客 cookie 合并 */
    private void seedConfiguredCookie() {
        if (cookie == null || cookie.isBlank()) return;
        for (String pair : cookie.trim().split(";")) {
            String t = pair.trim();
            int eq = t.indexOf('=');
            if (eq > 0) {
                putCookie("m.weibo.cn", t.substring(0, eq).trim(), t.substring(eq + 1).trim());
            }
        }
    }

    /**
     * 走新浪访客系统（Sina Visitor System）拿到游客身份 cookie，整套流程对照浏览器里那段 JS：
     *   1) POST passport.weibo.com/visitor/genvisitor  → 拿临时 tid
     *   2) GET  passport.weibo.com/visitor/visitor?a=incarnate&t=tid → 下发 SUB / SUBP（域是 .weibo.com）
     *   3) 把 SUB/SUBP 改挂到 m.weibo.cn 域（跨域 cookie 不会自动带过去），再访问一次 m.weibo.cn/
     *      让它回种 _T_WM。三者齐了 statuses/show 才会返回 ok:1。
     * @param force 为 true 时无视已就绪标记强制重握（cookie 过期场景）
     */
    private synchronized void ensureVisitorCookie(boolean force) {
        if (visitorReady && !force) return;
        try {
            // 1) genvisitor
            String fp = "{\"os\":\"1\",\"browser\":\"Gecko57,2,0,0\","
                    + "\"fonts\":\"undefined\",\"screenInfo\":\"1920*1080*24\",\"plugins\":\"\"}";
            Request genReq = new Request.Builder()
                    .url("https://passport.weibo.com/visitor/genvisitor")
                    .header("User-Agent", userAgent)
                    .header("Referer", "https://passport.weibo.com/visitor/visitor")
                    .post(new FormBody.Builder().add("cb", "gen_callback").add("fp", fp).build())
                    .build();
            String genBody;
            try (Response r = client.newCall(genReq).execute()) {
                genBody = r.body() == null ? "" : r.body().string();
            }
            Matcher tm = Pattern.compile("\"tid\":\"([^\"]+)\"").matcher(genBody);
            if (!tm.find()) {
                throw new IOException("访客系统未返回 tid: " + genBody);
            }
            String tid = tm.group(1);

            // 2) incarnate —— SUB/SUBP 由 CookieJar 收进 passport.weibo.com 名下
            String incUrl = "https://passport.weibo.com/visitor/visitor?a=incarnate&t="
                    + tid + "&w=2&c=100&gc=&cb=cross_domain&from=weibo&_rand=" + Math.random();
            Request incReq = new Request.Builder()
                    .url(incUrl)
                    .header("User-Agent", userAgent)
                    .header("Referer", "https://passport.weibo.com/visitor/visitor")
                    .build();
            try (Response r = client.newCall(incReq).execute()) {
                if (r.body() != null) r.body().close();
            }

            // 3) 把 SUB/SUBP 改挂到 m.weibo.cn 域（CookieJar 按 host 存，跨域不会自动带）
            for (Cookie c : client.cookieJar()
                    .loadForRequest(HttpUrl.parse("https://passport.weibo.com/"))) {
                if ("SUB".equals(c.name()) || "SUBP".equals(c.name())) {
                    putCookie("m.weibo.cn", c.name(), c.value());
                }
            }

            // 4) 访问一次 m.weibo.cn 首页换取 _T_WM（跟随重定向，CookieJar 自动收）
            Request warm = new Request.Builder()
                    .url("https://m.weibo.cn/")
                    .header("User-Agent", userAgent)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .build();
            try (Response r = client.newCall(warm).execute()) {
                if (r.body() != null) r.body().close();
            }
            visitorReady = true;
        } catch (IOException e) {
            // 握手失败不直接抛：也许配置了登录 Cookie 能兜住，让后续 statuses/show 去决定成败
            visitorReady = false;
        }
    }

    /** 往 jar 里写一条挂在指定 host 名下的 cookie */
    private void putCookie(String host, String name, String value) {
        Cookie c = new Cookie.Builder()
                .domain(host).path("/").name(name).value(value).build();
        client.cookieJar().saveFromResponse(
                HttpUrl.parse("https://" + host + "/"), Collections.singletonList(c));
    }

    /**
     * 媒体代理下载：微博图片(sinaimg.cn)/视频(weibocdn) 都有防盗链，直接在浏览器打开会 403，
     * 必须带 Referer: https://weibo.com/ 才放行。这里服务端代拉媒体并原样流回前端，
     * 前端就能用一个同源、干净的 URL 直接 <img>/<video> 或下载，不用关心防盗链。
     * 透传 Range：<video> 在线播放靠 Range 请求做拖动/seek，不转发的话进度条拖不动。
     */
    public void proxyMedia(String mediaUrl, String range, HttpServletResponse response) {
        if (mediaUrl == null || mediaUrl.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR);
        }
        HttpUrl u = HttpUrl.parse(mediaUrl);
        if (u == null || !isAllowedMediaHost(u.host())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "非法的媒体地址，仅允许代理微博 CDN 资源");
        }
        Request.Builder rb = new Request.Builder()
                .url(u)
                .header("User-Agent", userAgent)
                // 关键：补上微博 Referer 绕过防盗链
                .header("Referer", "https://weibo.com/");
        if (range != null && !range.isBlank()) {
            rb.header("Range", range);
        }
        try (Response r = client.newCall(rb.build()).execute()) {
            // 200(整段) / 206(分段) 都算正常；其余按失败处理
            if ((r.code() != 200 && r.code() != 206) || r.body() == null) {
                throw new BusinessException(ErrorCode.NETWORK_ERROR, "拉取媒体失败: HTTP " + r.code());
            }
            response.setStatus(r.code());
            response.setHeader("Accept-Ranges", "bytes");
            // Content-Type 矫正：实况图 mov 上游回的是 application/octet-stream，浏览器会当下载而非播放，
            // 按最终地址扩展名修正成正确 MIME（mov→video/quicktime）
            response.setHeader("Content-Type", resolveContentType(r));
            passThroughHeader(r, response, "Content-Length", null);
            passThroughHeader(r, response, "Content-Range", null);
            try (InputStream in = r.body().byteStream(); OutputStream out = response.getOutputStream()) {
                in.transferTo(out);
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.NETWORK_ERROR, "代理媒体异常: " + e.getMessage());
        }
    }

    /**
     * 决定回给前端的 Content-Type：
     * mov 强制 video/quicktime（实况图能在 &lt;video&gt; 里内联播放）；
     * 上游已给出明确且非 octet-stream 的类型就沿用；否则按最终地址扩展名兜底。
     */
    private String resolveContentType(Response upstream) {
        // 跟随重定向后的最终地址，签名参数在 query 里，路径才是真实文件名
        String path = upstream.request().url().encodedPath().toLowerCase();
        if (path.endsWith(".mov")) return "video/quicktime";
        String upstreamCt = upstream.header("Content-Type");
        if (upstreamCt != null && !upstreamCt.isBlank()
                && !upstreamCt.startsWith("application/octet-stream")) {
            return upstreamCt;
        }
        if (path.endsWith(".mp4")) return "video/mp4";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".gif")) return "image/gif";
        if (path.endsWith(".webp")) return "image/webp";
        return upstreamCt != null && !upstreamCt.isBlank() ? upstreamCt : "application/octet-stream";
    }

    /** 把上游响应头原样透传给前端；上游缺失时用 fallback（fallback 为 null 则不设） */
    private void passThroughHeader(Response upstream, HttpServletResponse response,
                                   String name, String fallback) {
        String v = upstream.header(name);
        if (v != null) {
            response.setHeader(name, v);
        } else if (fallback != null) {
            response.setHeader(name, fallback);
        }
    }

    private boolean isAllowedMediaHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase();
        for (String s : ALLOWED_MEDIA_HOST_SUFFIX) {
            if (h.equals(s) || h.endsWith("." + s)) return true;
        }
        return false;
    }

    private String fetchJson(String url, String referer) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Referer", referer)
                // m.weibo.cn 的 ajax 接口靠这两个头判定是站内请求，缺了会被重定向到 H5 壳
                .header("X-Requested-With", "XMLHttpRequest")
                .header("MWeibo-Pwa", "1");
        // 不在这里手动塞 Cookie 头：CookieJar 会在发送时统一注入并覆盖手动头。
        // 配置的登录 Cookie 已在 seedConfiguredCookie 里写进 jar，会和游客 cookie 自动合并。
        try (Response response = client.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("请求失败: HTTP " + response.code());
            }
            return response.body() == null ? "" : response.body().string();
        }
    }

    private String getRealUrl(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", userAgent)
                .build();
        try (Response response = client.newCall(request).execute()) {
            return response.request().url().toString();
        }
    }

    /** 视频卡片对象 id：形如 1034:5311259936227398，出现在 video.weibo.com/show?fid= 或 /show/ 路径里 */
    private String extractVideoFid(String url) {
        if (url == null) return null;
        Matcher m = Pattern.compile("(?:fid=|/show/)(\\d{2,}:\\d{6,})").matcher(url);
        if (m.find()) return m.group(1);
        return null;
    }

    private String extractStatusId(String url) {
        if (url == null) return null;
        // m.weibo.cn/status/{id}、m.weibo.cn/detail/{id}
        Matcher m = Pattern.compile("(?:status|detail)/([A-Za-z0-9]+)").matcher(url);
        if (m.find()) return m.group(1);
        // weibo.com/{uid}/{bid} 或 m.weibo.cn/{uid}/{bid}，bid 是 base62
        m = Pattern.compile("weibo\\.(?:com|cn)/(?:u/)?\\d+/([A-Za-z0-9]+)").matcher(url);
        if (m.find()) return m.group(1);
        // 查询参数形式：?id=、?weibo_id=（不含冒号，避免误吞视频卡片 fid）
        m = Pattern.compile("[?&](?:id|weibo_id)=([A-Za-z0-9]+)").matcher(url);
        if (m.find()) return m.group(1);
        return null;
    }

    private String extractUrl(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("(https?://\\S+)").matcher(text);
        if (m.find()) return m.group(1).replaceAll("[。！？，]$", "");
        return null;
    }

    /** 极简进程内 CookieJar：按 host 存最新 cookie，足够单机去水印场景用；不做持久化 / 过期清理 */
    private static final class InMemoryCookieJar implements CookieJar {
        private final Map<String, Map<String, Cookie>> store = new ConcurrentHashMap<>();

        @Override
        public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            Map<String, Cookie> jar =
                    store.computeIfAbsent(url.host(), k -> new ConcurrentHashMap<>());
            for (Cookie c : cookies) {
                jar.put(c.name(), c);
            }
        }

        @Override
        public List<Cookie> loadForRequest(HttpUrl url) {
            Map<String, Cookie> jar = store.get(url.host());
            return jar == null ? new ArrayList<>() : new ArrayList<>(jar.values());
        }
    }

    // ============================================================
    //  直接跑 main() 的本地调试入口（不启 Spring）
    //  两种用法：
    //    1) IDE 里 Run，不传参 → 用下面 FALLBACK 常量里的样本链接
    //    2) 命令行 / IDE Run Configuration 里给参数 → 第一个参数当分享文案
    //  Controller 走的是同一个 extractMedia(...)，行为一致。
    // ============================================================
    /** 控制台分类打印：每条同时给「可直接打开的代理链接」和「原始直链」 */
    private static void printSection(String proxyBase, String name, List<String> urls) {
        System.out.println("--- " + name + " (" + (urls == null ? 0 : urls.size()) + ") ---");
        if (urls == null || urls.isEmpty()) return;
        int i = 1;
        for (String u : urls) {
            System.out.println("[" + i++ + "] 可直接打开: " + proxyOf(proxyBase, u));
            System.out.println("    原始直链 : " + u);
        }
    }

    private static String proxyOf(String proxyBase, String mediaUrl) {
        return proxyBase + "/api/weibo/download?url="
                + java.net.URLEncoder.encode(mediaUrl, java.nio.charset.StandardCharsets.UTF_8);
    }

    public static void main(String[] args) {
        String FALLBACK = "https://video.weibo.com/show?fid=1034:5306132303118339";
        String shareText = (args != null && args.length > 0 && !args[0].isBlank()) ? args[0] : FALLBACK;

        WeiboMediaService svc = new WeiboMediaService();
        svc.userAgent = DEFAULT_UA;
        // 公开微博一般不需要 Cookie；遇到需要登录可见的微博时把 Cookie 贴这里
        svc.cookie = "";

        // 代理前缀：main 不启 web 服务，这里假设你另外把应用跑在了 localhost:8080；
        // 想换地址就传第二个参数（如 http://192.168.1.5:8080）
        String proxyBase = (args != null && args.length > 1 && !args[1].isBlank())
                ? args[1].replaceAll("/+$", "") : "http://localhost:8080";

        try {
            WeiboMediaVO vo = svc.extractMedia(shareText);
            System.out.println("================ 解析成功 ================");
            System.out.println("标题: " + vo.getTitle());
            System.out.println("作者: " + vo.getAuthor());
            System.out.println("类型: " + vo.getType());
            System.out.println();
            System.out.println(">>> 下面「可直接打开」的链接需先把应用启动在 " + proxyBase + " <<<");
            printSection(proxyBase, "图片(无水印)", vo.getImages());
            printSection(proxyBase, "实况图(live)", vo.getLivePhotos());
            printSection(proxyBase, "视频", vo.getVideos());
            System.out.println("==========================================");
        } catch (BusinessException e) {
            System.err.println("❌ 解析失败: [" + e.getErrorCode().getCode() + "] " + e.getMessage());
            System.err.println("排查：");
            System.err.println("  - 短链失效 → 直接用完整 m.weibo.cn/status/{id} 链接重试");
            System.err.println("  - 需要登录可见 → 在 application.yaml 的 weibo.cookie 里贴上 m.weibo.cn 的 Cookie");
        }
    }
}
