package com.madness.mqmremovemark.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.madness.mqmremovemark.common.BusinessException;
import com.madness.mqmremovemark.common.ErrorCode;
import com.madness.mqmremovemark.model.DouyinMediaVO;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DouyinMediaService {

    private static final ObjectMapper mapper = new ObjectMapper();

    /** 走 iesdouyin.com 移动 share 页时必须用移动 UA，否则会被切回 www.douyin.com 的 SPA 壳 */
    private static final String DEFAULT_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) "
                    + "AppleWebKit/605.1.15 (KHTML, like Gecko) "
                    + "Version/16.0 Mobile/15E148 Safari/604.1";

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    @Value("${douyin.user-agent:" + DEFAULT_UA + "}")
    private String userAgent;

    /** 走 iesdouyin share 页路径时不需要 Cookie；留着这字段是给未来改回 www.douyin.com 路径用的 */
    @Value("${douyin.cookie:}")
    private String cookie;

    public DouyinMediaVO extractMedia(String rawShareText) {
        String shareUrl = extractUrl(rawShareText);
        if (shareUrl == null) {
            throw new BusinessException(ErrorCode.URL_NOT_FOUND);
        }
        try {
            // 先直接从输入 URL 提 ID：PC 端的 modal_id= 形式经过 redirect 会丢失 query，必须先就地解
            String awemeId = extractAwemeId(shareUrl);
            if (awemeId == null) {
                // 提不到 → 多半是 v.douyin.com 短链，跑一遍 redirect 再试
                String resolvedUrl = getRealUrl(shareUrl);
                awemeId = extractAwemeId(resolvedUrl);
            }
            if (awemeId == null) {
                throw new BusinessException(ErrorCode.DATA_PARSE_ERROR, "未提取到视频ID");
            }
            // 关键：永远抓 iesdouyin 的 share 页面，那里的 HTML 内嵌完整 _ROUTER_DATA；
            // www.douyin.com/video/{id} 现在是空壳 + byted_acrawler 反爬，正常 OkHttp 请求拿不到数据
            String targetUrl = "https://www.iesdouyin.com/share/video/" + awemeId + "/";
            String html = fetchHtml(targetUrl);
            if (html.length() < 2000) {
                throw new BusinessException(ErrorCode.DATA_PARSE_ERROR,
                        "页面长度仅 " + html.length() + " 字节，疑似被风控");
            }
            JsonNode videoDetail = parseVideoDetail(html);
            if (videoDetail == null) {
                try {
                    java.nio.file.Path p = java.nio.file.Paths.get("/tmp/douyin_dump.html");
                    java.nio.file.Files.writeString(p, html);
                    System.err.println("[debug] HTML 长度=" + html.length()
                            + " 字节，已写入 " + p.toAbsolutePath());
                } catch (Exception dumpErr) {
                    System.err.println("[debug] HTML 落盘失败: " + dumpErr);
                }
                throw new BusinessException(ErrorCode.DATA_PARSE_ERROR,
                        "未找到视频数据节点，抖音可能又改版了，看 /tmp/douyin_dump.html");
            }
            return buildVO(videoDetail);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.NETWORK_ERROR, "网络请求异常: " + e.getMessage());
        }
    }

    private DouyinMediaVO buildVO(JsonNode videoDetail) {
        String title = videoDetail.path("desc").asText();
        String author = videoDetail.path("author").path("nickname").asText();
        List<String> images = new ArrayList<>();
        List<String> videos = new ArrayList<>();
        String cover = null;
        String type;

        JsonNode imagesNode = videoDetail.path("images");
        if (imagesNode.isArray() && imagesNode.size() > 0) {
            type = "image";
            for (JsonNode img : imagesNode) {
                JsonNode urlList = img.path("url_list");
                if (urlList.isArray() && urlList.size() > 0) {
                    images.add(urlList.get(0).asText());
                }
                // 老格式：每张图自带 live_photo 视频片段
                JsonNode livePhoto = img.path("live_photo").path("url_list");
                if (livePhoto.isArray() && livePhoto.size() > 0) {
                    videos.add(livePhoto.get(0).asText());
                }
            }
            // 实况图 / slides 新格式：整组图共享一个顶层 video.play_addr（playwm 链接）。
            // 注意：抖音对「纯图集 slideshow」也塞了一个顶层 video 节点，但 duration=0、
            // url_list[0] 里的 video_id 是 CDN 路径而非真正的 video_id，请求会返回 text/plain。
            // 必须用 duration>0 把这种占位符过滤掉。
            JsonNode videoNode = videoDetail.path("video");
            long duration = videoNode.path("duration").asLong(0);
            if (duration > 0) {
                JsonNode topVideo = videoNode.path("play_addr").path("url_list");
                if (topVideo.isArray() && topVideo.size() > 0) {
                    videos.add(topVideo.get(0).asText().replace("playwm", "play"));
                }
            }
            // 封面始终补上（即使没真视频，slideshow 也有平台生成的封面图）
            JsonNode topCover = videoNode.path("cover").path("url_list");
            if (topCover.isArray() && topCover.size() > 0) {
                cover = topCover.get(0).asText();
            }
        } else {
            type = "video";
            JsonNode videoNode = videoDetail.path("video");
            JsonNode playList = videoNode.path("play_addr").path("url_list");
            if (playList.isArray() && playList.size() > 0) {
                videos.add(playList.get(0).asText().replace("playwm", "play"));
            }
            JsonNode coverList = videoNode.path("cover").path("url_list");
            if (coverList.isArray() && coverList.size() > 0) {
                cover = coverList.get(0).asText();
            }
            JsonNode dynamicCover = videoNode.path("dynamic_cover").path("url_list");
            if (dynamicCover.isArray() && dynamicCover.size() > 0) {
                videos.add(dynamicCover.get(0).asText());
            }
        }
        return new DouyinMediaVO(title, author, type, images, videos, cover);
    }

    private String fetchHtml(String url) throws IOException {
        // 不要手动设 Accept-Encoding：OkHttp 默认透明解 gzip，手动设它就不解了
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Referer", "https://www.douyin.com/")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("sec-ch-ua", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"")
                .header("sec-ch-ua-mobile", "?0")
                .header("sec-ch-ua-platform", "\"Windows\"")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-User", "?1")
                .header("Upgrade-Insecure-Requests", "1");
        if (cookie != null && !cookie.isBlank()) {
            builder.header("Cookie", cookie.trim());
        }
        try (Response response = client.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("请求失败: HTTP " + response.code());
            }
            return response.body() == null ? "" : response.body().string();
        }
    }

    private JsonNode parseVideoDetail(String html) throws Exception {
        Document doc = Jsoup.parse(html);

        // 1) script#RENDER_DATA —— URL-encoded JSON，老版抖音 PC 端用法
        Element scriptTag = doc.selectFirst("script#RENDER_DATA");
        if (scriptTag != null) {
            JsonNode found = findVideoDataNode(decodeAndParse(scriptTag.data()));
            if (found != null) return found;
        }

        // 2) script#__NUXT__ —— 早期 Nuxt 渲染入口
        scriptTag = doc.selectFirst("script#__NUXT__");
        if (scriptTag != null) {
            try {
                JsonNode found = findVideoDataNode(mapper.readTree(scriptTag.data()));
                if (found != null) return found;
            } catch (Exception ignored) {
            }
        }

        // 3) window._SSR_HYDRATED_DATA / window._ROUTER_DATA —— 新版 PC 抖音
        for (String varName : new String[]{
                "_SSR_HYDRATED_DATA", "_ROUTER_DATA", "_SSR_INITIAL_DATA"
        }) {
            JsonNode found = matchWindowVar(html, varName);
            if (found != null) return found;
        }

        // 4) 兜底：遍历所有 <script>，含 aweme_id 关键字的拿来解析
        Elements scripts = doc.select("script");
        for (Element script : scripts) {
            String content = script.data();
            if (content.length() > 100
                    && (content.contains("aweme_id") || content.contains("videoDetailData"))) {
                try {
                    JsonNode found = findVideoDataNode(decodeAndParse(content));
                    if (found != null) return found;
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private JsonNode matchWindowVar(String html, String varName) {
        Pattern p = Pattern.compile(
                "window\\." + Pattern.quote(varName) + "\\s*=\\s*(\\{[\\s\\S]*?\\})\\s*;?\\s*</script>",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        if (m.find()) {
            try {
                return findVideoDataNode(mapper.readTree(m.group(1).replace("undefined", "null")));
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private JsonNode decodeAndParse(String data) throws Exception {
        try {
            return mapper.readTree(URLDecoder.decode(data, StandardCharsets.UTF_8.name()));
        } catch (Exception e) {
            return mapper.readTree(data);
        }
    }

    private JsonNode findVideoDataNode(JsonNode node) {
        if (node == null) return null;
        if (node.has("aweme_id")) return node;
        if (node.isObject()) {
            if (node.has("app")) {
                JsonNode app = node.get("app");
                if (app.has("videoDetailData")) return app.get("videoDetailData");
                if (app.has("videoData")) return app.get("videoData");
            }
            Iterator<String> fieldNames = node.fieldNames();
            while (fieldNames.hasNext()) {
                JsonNode result = findVideoDataNode(node.get(fieldNames.next()));
                if (result != null) return result;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode result = findVideoDataNode(child);
                if (result != null) return result;
            }
        }
        return null;
    }

    private String extractUrl(String text) {
        if (text == null) return null;
        Pattern pattern = Pattern.compile("(https?://\\S+)");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) return matcher.group(1).replaceAll("[。！？，]$", "");
        return null;
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

    private String extractAwemeId(String url) {
        if (url == null) return null;
        // 路径形式：/video/{id}、/note/{id}、/slides/{id}（实况图）、/share/{...}/{id}
        Matcher m = Pattern.compile("(?:share/)?(?:video|note|slides)/([0-9]+)").matcher(url);
        if (m.find()) return m.group(1);
        // 查询参数形式：PC 端个人主页 / 精选 / discover 复制链接得到的 ?modal_id={id}
        m = Pattern.compile("modal_id=([0-9]+)").matcher(url);
        if (m.find()) return m.group(1);
        return null;
    }

    // ============================================================
    //  直接跑 main() 的本地调试入口（不启 Spring）
    //  两种用法：
    //    1) IDE 里 Run，不传参 → 用下面 FALLBACK 常量里的样本链接
    //    2) 命令行 / IDE Run Configuration 里给参数 → 第一个参数当分享文案
    //  Controller 走的是同一个 extractMedia(...)，行为一致。
    // ============================================================
    public static void main(String[] args) {
        String FALLBACK = "1.56 Fhb:/ :2pm V@L.JV 12/02 有没有姐妹也觉得游泳久了肩膀会变宽呀 # 游泳 # 泳衣拍摄 # 这样的身材能打多少分  https://v.douyin.com/qqn76szbnIs/ 复制此链接，打开Dou音搜索，直接观看视频！";
        String shareText = (args != null && args.length > 0 && !args[0].isBlank()) ? args[0] : FALLBACK;

        DouyinMediaService svc = new DouyinMediaService();
        svc.userAgent = DEFAULT_UA;
        // 当前 iesdouyin 路径不需要 Cookie；要切回 www.douyin.com 时再把 Cookie 贴这里
        svc.cookie = "";

        try {
            DouyinMediaVO vo = svc.extractMedia(shareText);
            System.out.println("================ 解析成功 ================");
            System.out.println("标题: " + vo.getTitle());
            System.out.println("作者: " + vo.getAuthor());
            System.out.println("类型: " + vo.getType());
            System.out.println("封面: " + vo.getCover());
            System.out.println("--- 图片 ---");
            vo.getImages().forEach(System.out::println);
            System.out.println("--- 视频 ---");
            vo.getVideos().forEach(System.out::println);
            System.out.println("==========================================");
        } catch (BusinessException e) {
            System.err.println("❌ 解析失败: [" + e.getErrorCode().getCode() + "] " + e.getMessage());
            System.err.println("排查：");
            System.err.println("  - 短链失效 → 直接用完整 iesdouyin / www.douyin.com 链接重试");
            System.err.println("  - 抖音改版 → 看落盘的 /tmp/douyin_dump.html 里 _ROUTER_DATA 还在不在");
        }
    }
}
