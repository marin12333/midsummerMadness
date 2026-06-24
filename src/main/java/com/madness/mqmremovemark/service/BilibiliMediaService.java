package com.madness.mqmremovemark.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.madness.mqmremovemark.common.BusinessException;
import com.madness.mqmremovemark.common.ErrorCode;
import com.madness.mqmremovemark.model.BilibiliMediaVO;
import jakarta.servlet.http.HttpServletResponse;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class BilibiliMediaService {

    private static final ObjectMapper mapper = new ObjectMapper();

    /** 桌面 UA 即可；B站 web API 对 UA 不挑，但带上更稳 */
    private static final String DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** B站接口/资源大多校验 Referer 为站内地址 */
    private static final String REFERER = "https://www.bilibili.com";

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    /** 媒体代理域名白名单（防 SSRF）：B站图片 CDN(hdslb) + 视频 CDN(bilivideo) */
    private static final Set<String> ALLOWED_MEDIA_HOST_SUFFIX =
            Set.of("hdslb.com", "bilivideo.com", "akamaized.net", "bilivideo.cn");

    @Value("${bilibili.user-agent:" + DEFAULT_UA + "}")
    private String userAgent;

    /** 部分会员/付费视频需要登录 Cookie（SESSDATA）；公开内容留空即可 */
    @Value("${bilibili.cookie:}")
    private String cookie;

    public BilibiliMediaVO extractMedia(String rawShareText) {
        String shareUrl = extractUrl(rawShareText);
        if (shareUrl == null) {
            throw new BusinessException(ErrorCode.URL_NOT_FOUND);
        }
        try {
            // b23.tv 短链先跟一遍 302 拿到真实地址
            if (shareUrl.contains("b23.tv") || shareUrl.contains("bili2233.cn")) {
                shareUrl = getRealUrl(shareUrl);
            }
            // 1) 视频：/video/BVxxx、/video/avxxx，或纯 BV 号
            String bvid = extractBvid(shareUrl);
            String aid = bvid == null ? extractAid(shareUrl) : null;
            if (bvid != null || aid != null) {
                return buildVideoVO(bvid, aid);
            }
            // 2) 图文动态 / 专栏：t.bilibili.com/{id}、/opus/{id}
            String dynId = extractDynamicId(shareUrl);
            if (dynId != null) {
                return buildDynamicVO(dynId);
            }
            throw new BusinessException(ErrorCode.DATA_PARSE_ERROR, "无法识别的B站链接（仅支持视频 / 图文动态）");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.NETWORK_ERROR, "网络请求异常: " + e.getMessage());
        }
    }

    // ========================== 视频 ==========================

    private BilibiliMediaVO buildVideoVO(String bvid, String aid) throws IOException {
        // 1) view 接口拿标题/作者/封面/cid（cid 是取播放地址的钥匙）
        String idParam = bvid != null ? "bvid=" + bvid : "aid=" + aid;
        JsonNode view = getJson("https://api.bilibili.com/x/web-interface/view?" + idParam);
        if (view.path("code").asInt(-1) != 0) {
            throw new BusinessException(ErrorCode.NOTE_EMPTY,
                    "视频信息获取失败: " + view.path("message").asText());
        }
        JsonNode data = view.path("data");
        String title = data.path("title").asText("");
        String author = data.path("owner").path("name").asText("");
        String cover = data.path("pic").asText(null);
        // 多 P 视频取第一个分 P 的 cid
        long cid = data.path("cid").asLong(0);
        JsonNode pages = data.path("pages");
        if (cid == 0 && pages.isArray() && pages.size() > 0) {
            cid = pages.get(0).path("cid").asLong(0);
        }

        List<String> videos = new ArrayList<>();
        // 2) playurl：platform=html5 + fnval=1 → 无登录可拿「单文件 MP4」（无需音视频合并）
        //    qn=80 请求 1080P，拿不到会自动降到可用的最高档（无登录通常 720P）
        String puBase = "https://api.bilibili.com/x/player/playurl?" + idParam
                + "&cid=" + cid + "&qn=80&platform=html5&fnval=1";
        JsonNode pu = getJson(puBase);
        if (pu.path("code").asInt(-1) == 0) {
            JsonNode durl = pu.path("data").path("durl");
            // durl 多段是同一视频的连续分片，绝大多数现代视频只有 1 段，取第一段即可
            if (durl.isArray() && durl.size() > 0) {
                String u = durl.get(0).path("url").asText("");
                if (!u.isEmpty()) videos.add(u);
            }
        }
        return new BilibiliMediaVO(title, author, "video", new ArrayList<>(), videos, cover);
    }

    // ========================== 图文动态 ==========================

    private BilibiliMediaVO buildDynamicVO(String dynId) throws IOException {
        JsonNode root = getJson(
                "https://api.bilibili.com/x/polymer/web-dynamic/v1/detail?id=" + dynId);
        if (root.path("code").asInt(-1) != 0) {
            throw new BusinessException(ErrorCode.NOTE_EMPTY,
                    "动态获取失败: " + root.path("message").asText());
        }
        JsonNode item = root.path("data").path("item");
        JsonNode modules = item.path("modules");
        String author = modules.path("module_author").path("name").asText("");
        JsonNode major = modules.path("module_dynamic").path("major");

        List<String> images = new ArrayList<>();
        String title = "";

        // 图文动态(DRAW)：major.draw.items[].src
        JsonNode drawItems = major.path("draw").path("items");
        if (drawItems.isArray()) {
            for (JsonNode it : drawItems) {
                addImage(images, it.path("src").asText(""));
            }
        }
        // 新版图文(OPUS)：major.opus.pics[].url，标题在 opus.title
        JsonNode opus = major.path("opus");
        if (!opus.isMissingNode()) {
            title = opus.path("title").asText("");
            for (JsonNode pic : opus.path("pics")) {
                addImage(images, pic.path("url").asText(""));
            }
        }
        // 专栏(ARTICLE)：封面 major.article.covers[]
        JsonNode article = major.path("article");
        if (!article.isMissingNode()) {
            if (title.isEmpty()) title = article.path("title").asText("");
            for (JsonNode c : article.path("covers")) {
                addImage(images, c.asText(""));
            }
        }
        if (images.isEmpty()) {
            throw new BusinessException(ErrorCode.NOTE_EMPTY, "该动态没有可提取的图片（可能是纯文字 / 视频动态）");
        }
        String cover = images.get(0);
        return new BilibiliMediaVO(title, author, "image", images, new ArrayList<>(), cover);
    }

    private void addImage(List<String> images, String url) {
        String clean = toNoWatermarkImage(url);
        if (clean != null && !clean.isEmpty()) {
            images.add(clean);
        }
    }

    /**
     * B站图片去水印：图片本身不带水印，URL 末尾的 @宽w_高h_质量 只是 CDN 实时缩放/转码参数，
     * 去掉它即拿原图原画质。同时把协议相对地址(//开头)补成 https。
     */
    private String toNoWatermarkImage(String url) {
        if (url == null || url.isEmpty()) return "";
        String u = url.startsWith("//") ? "https:" + url : url;
        int at = u.indexOf('@');
        return at > 0 ? u.substring(0, at) : u;
    }

    // ========================== 媒体代理 ==========================

    /**
     * 媒体代理：B站视频(bilivideo)有防盗链，需带 Referer: bilibili.com；图片(hdslb)虽不防，
     * 也统一走代理，前端拿同源链接即可直接 <img>/<video>/下载。透传 Range 以支持视频拖动。
     */
    public void proxyMedia(String mediaUrl, String range, HttpServletResponse response) {
        if (mediaUrl == null || mediaUrl.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR);
        }
        HttpUrl u = HttpUrl.parse(mediaUrl);
        if (u == null || !isAllowedMediaHost(u.host())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "非法的媒体地址，仅允许代理B站 CDN 资源");
        }
        Request.Builder rb = new Request.Builder()
                .url(u)
                .header("User-Agent", userAgent)
                .header("Referer", REFERER);
        if (range != null && !range.isBlank()) {
            rb.header("Range", range);
        }
        try (Response r = client.newCall(rb.build()).execute()) {
            if ((r.code() != 200 && r.code() != 206) || r.body() == null) {
                throw new BusinessException(ErrorCode.NETWORK_ERROR, "拉取媒体失败: HTTP " + r.code());
            }
            response.setStatus(r.code());
            response.setHeader("Accept-Ranges", "bytes");
            response.setHeader("Content-Type", resolveContentType(r));
            passThroughHeader(r, response, "Content-Length");
            passThroughHeader(r, response, "Content-Range");
            try (InputStream in = r.body().byteStream(); OutputStream out = response.getOutputStream()) {
                in.transferTo(out);
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.NETWORK_ERROR, "代理媒体异常: " + e.getMessage());
        }
    }

    private String resolveContentType(Response upstream) {
        String path = upstream.request().url().encodedPath().toLowerCase();
        String upstreamCt = upstream.header("Content-Type");
        if (upstreamCt != null && !upstreamCt.isBlank()
                && !upstreamCt.startsWith("application/octet-stream")) {
            return upstreamCt;
        }
        if (path.endsWith(".mp4") || path.endsWith(".m4s")) return "video/mp4";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".gif")) return "image/gif";
        if (path.endsWith(".webp")) return "image/webp";
        return upstreamCt != null && !upstreamCt.isBlank() ? upstreamCt : "application/octet-stream";
    }

    private void passThroughHeader(Response upstream, HttpServletResponse response, String name) {
        String v = upstream.header(name);
        if (v != null) response.setHeader(name, v);
    }

    private boolean isAllowedMediaHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase();
        for (String s : ALLOWED_MEDIA_HOST_SUFFIX) {
            if (h.equals(s) || h.endsWith("." + s)) return true;
        }
        return false;
    }

    // ========================== 通用 ==========================

    private JsonNode getJson(String url) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Referer", REFERER)
                .header("Accept", "application/json, text/plain, */*");
        if (cookie != null && !cookie.isBlank()) {
            builder.header("Cookie", cookie.trim());
        }
        try (Response response = client.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("请求失败: HTTP " + response.code());
            }
            return mapper.readTree(response.body() == null ? "" : response.body().string());
        }
    }

    private String getRealUrl(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .head()
                .build();
        try (Response response = client.newCall(request).execute()) {
            return response.request().url().toString();
        }
    }

    private String extractUrl(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("(https?://\\S+)").matcher(text);
        if (m.find()) return m.group(1).replaceAll("[。！？，]$", "");
        // 没有 http 前缀时，兼容用户直接粘 BV 号
        m = Pattern.compile("(BV[0-9A-Za-z]{10})").matcher(text);
        if (m.find()) return "https://www.bilibili.com/video/" + m.group(1);
        return null;
    }

    /** /video/BVxxxx 或裸 BV 号；BV 号固定 BV + 10 位字母数字 */
    private String extractBvid(String url) {
        Matcher m = Pattern.compile("(BV[0-9A-Za-z]{10})").matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /** /video/av12345 或 ?aid=12345 */
    private String extractAid(String url) {
        Matcher m = Pattern.compile("(?:/av|aid=)(\\d+)").matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /** t.bilibili.com/{id}、/opus/{id}、/dynamic/{id}，id 是纯数字 */
    private String extractDynamicId(String url) {
        Matcher m = Pattern.compile("(?:t\\.bilibili\\.com/|/opus/|/dynamic/)(\\d+)").matcher(url);
        return m.find() ? m.group(1) : null;
    }

    // ============================================================
    //  直接跑 main() 的本地调试入口（不启 Spring）
    //    1) IDE Run 不传参 → 用 FALLBACK
    //    2) 传参 → 第一个参数当分享文案；第二个参数可指定代理地址（默认 http://localhost:8080）
    // ============================================================
    public static void main(String[] args) {
        String FALLBACK = "https://www.bilibili.com/video/BV1xx411c7mD";
        String shareText = (args != null && args.length > 0 && !args[0].isBlank()) ? args[0] : FALLBACK;
        String proxyBase = (args != null && args.length > 1 && !args[1].isBlank())
                ? args[1].replaceAll("/+$", "") : "http://localhost:8080";

        BilibiliMediaService svc = new BilibiliMediaService();
        svc.userAgent = DEFAULT_UA;
        svc.cookie = "";

        try {
            BilibiliMediaVO vo = svc.extractMedia(shareText);
            System.out.println("================ 解析成功 ================");
            System.out.println("标题: " + vo.getTitle());
            System.out.println("作者: " + vo.getAuthor());
            System.out.println("类型: " + vo.getType());
            System.out.println("封面: " + vo.getCover());
            System.out.println();
            System.out.println(">>> 「可直接打开」链接需先把应用启动在 " + proxyBase + " <<<");
            printSection(proxyBase, "图片(无水印)", vo.getImages());
            printSection(proxyBase, "视频", vo.getVideos());
            System.out.println("==========================================");
        } catch (BusinessException e) {
            System.err.println("❌ 解析失败: [" + e.getErrorCode().getCode() + "] " + e.getMessage());
        }
    }

    private static void printSection(String proxyBase, String name, List<String> urls) {
        System.out.println("--- " + name + " (" + (urls == null ? 0 : urls.size()) + ") ---");
        if (urls == null || urls.isEmpty()) return;
        int i = 1;
        for (String u : urls) {
            System.out.println("[" + i++ + "] 可直接打开: " + proxyBase + "/api/bilibili/download?url="
                    + java.net.URLEncoder.encode(u, java.nio.charset.StandardCharsets.UTF_8));
            System.out.println("    原始直链 : " + u);
        }
    }
}
