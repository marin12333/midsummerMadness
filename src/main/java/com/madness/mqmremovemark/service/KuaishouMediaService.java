package com.madness.mqmremovemark.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.madness.mqmremovemark.common.BusinessException;
import com.madness.mqmremovemark.common.ErrorCode;
import com.madness.mqmremovemark.model.KuaishouMediaVO;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class KuaishouMediaService {

    private static final ObjectMapper mapper = new ObjectMapper();

    /** 必须用移动 UA，桌面 UA 打 www.kuaishou.com/short-video/{id} 只会返回 63 字节空响应 */
    private static final String DEFAULT_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) "
                    + "AppleWebKit/605.1.15 (KHTML, like Gecko) "
                    + "Version/16.0 Mobile/15E148 Safari/604.1";

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    @Value("${kuaishou.user-agent:" + DEFAULT_UA + "}")
    private String userAgent;

    public KuaishouMediaVO extractMedia(String rawShareText) {
        String shareUrl = extractUrl(rawShareText);
        if (shareUrl == null) {
            throw new BusinessException(ErrorCode.URL_NOT_FOUND);
        }
        try {
            // OkHttp 自动跟 302：kuaishou.com/f/CODE → kuaishou.com/short-video/{id}
            //                  → m.gifshow.com/fw/photo/{id}（移动 UA 下数据在这页里）
            String html = fetchHtml(shareUrl);
            if (html.length() < 2000) {
                throw new BusinessException(ErrorCode.DATA_PARSE_ERROR,
                        "页面长度仅 " + html.length() + " 字节，疑似被风控或链接失效");
            }
            JsonNode container = findContentContainer(html);
            if (container == null) {
                try {
                    java.nio.file.Path p = java.nio.file.Paths.get("/tmp/kuaishou_dump.html");
                    java.nio.file.Files.writeString(p, html);
                    System.err.println("[debug] HTML 长度=" + html.length()
                            + " 字节，已写入 " + p.toAbsolutePath());
                } catch (Exception ignored) {
                }
                throw new BusinessException(ErrorCode.DATA_PARSE_ERROR,
                        "未找到 photo 节点，快手可能又改版了，看 /tmp/kuaishou_dump.html");
            }
            // 图集帖里 atlas 跟 photo 是同级节点（不在 photo 里面），所以从容器一起拿
            return buildVO(container.path("photo"), container.path("atlas"));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.NETWORK_ERROR, "网络请求异常: " + e.getMessage());
        }
    }

    private KuaishouMediaVO buildVO(JsonNode photo, JsonNode atlas) {
        String title = photo.path("caption").asText();
        String author = photo.path("userName").asText();
        // photoType: VIDEO / HORIZONTAL_ATLAS / VERTICAL_ATLAS / SINGLE_PICTURE / LIVE 等
        String photoType = photo.path("photoType").asText();
        // 图集判断优先看 atlas 节点（最可靠）；photoType 仅作兜底。注意 singlePicture 字段语义不可靠（图集帖也是 true）
        JsonNode atlasList = atlas.path("list");
        boolean hasAtlas = atlasList.isArray() && atlasList.size() > 0;
        boolean isImage = hasAtlas
                || photoType.toUpperCase().endsWith("_ATLAS")
                || "SINGLE_PICTURE".equalsIgnoreCase(photoType);
        String type = isImage ? "image" : "video";

        List<String> images = new ArrayList<>();
        List<String> videos = new ArrayList<>();
        String cover = firstCdnUrl(photo.path("coverUrls"));

        if (hasAtlas) {
            // 图集帖：atlas.list 是相对路径数组（/ufile/atlas/xxx_N.jpg），需要拼上 atlas.cdn[0]
            JsonNode cdnArr = atlas.path("cdn");
            String cdn = (cdnArr.isArray() && cdnArr.size() > 0) ? cdnArr.get(0).asText() : null;
            if (cdn != null && !cdn.isEmpty()) {
                for (JsonNode pathNode : atlasList) {
                    String relPath = pathNode.asText();
                    if (!relPath.isEmpty()) {
                        images.add("https://" + cdn + (relPath.startsWith("/") ? "" : "/") + relPath);
                    }
                }
            }
        } else if (isImage) {
            // 单图帖（SINGLE_PICTURE）：快手 share 接口里 coverUrls 就是图本体（不只是封面），
            // 数组里多个项是同一张图的 CDN 备份，取第一个即可
            String single = firstCdnUrl(photo.path("coverUrls"));
            if (single != null) images.add(single);
            // 兜底：抓不到 coverUrls 时再试一遍老字段名（猜测，未在真样本里见过）
            if (images.isEmpty()) {
                for (String f : new String[]{"imageUrls", "atlasUrls", "picUrls", "images"}) {
                    JsonNode arr = photo.path(f);
                    if (arr.isArray() && arr.size() > 0) {
                        for (JsonNode item : arr) {
                            String u = item.isTextual() ? item.asText() : item.path("url").asText();
                            if (!u.isEmpty()) images.add(u);
                        }
                        break;
                    }
                }
            }
        } else {
            // 视频帖：把所有候选 URL 收集起来，按「无水印优先 / 带水印兜底」排序后给前端做 CDN fallback
            // - 来源 A：manifest.adaptationSet[].representation[]（含 .url + .backupUrl[]）
            //          这里既有 _hd15 (无水印) 又有 _b_ (带水印) 两个清晰度档
            // - 来源 B：photo.mainMvUrls[]（一律是 _b_ 带水印版，作为最后兜底）
            // 去水印判断：URL path 不含 `_b_` 即无水印（hd15/hd14/hd10 progressive 都不带水印）
            LinkedHashSet<String> noWatermark = new LinkedHashSet<>();
            LinkedHashSet<String> withWatermark = new LinkedHashSet<>();
            for (JsonNode ada : photo.path("manifest").path("adaptationSet")) {
                for (JsonNode rep : ada.path("representation")) {
                    classify(rep.path("url").asText(""), noWatermark, withWatermark);
                    for (JsonNode bu : rep.path("backupUrl")) {
                        classify(bu.asText(""), noWatermark, withWatermark);
                    }
                }
            }
            for (JsonNode mv : photo.path("mainMvUrls")) {
                classify(mv.path("url").asText(""), noWatermark, withWatermark);
            }
            videos.addAll(noWatermark);
            videos.addAll(withWatermark);
        }
        return new KuaishouMediaVO(title, author, type, images, videos, cover);
    }

    private void classify(String url, LinkedHashSet<String> noWm, LinkedHashSet<String> wm) {
        if (url == null || url.isEmpty()) return;
        // 个别老版本 URL 会带 tt=b 占位参数（直接访问 404），删掉
        String cleaned = url.replace("&tt=b", "").replace("?tt=b&", "?").replace("?tt=b", "");
        if (cleaned.contains("_b_")) {
            wm.add(cleaned);
        } else {
            noWm.add(cleaned);
        }
    }

    private String firstCdnUrl(JsonNode arr) {
        if (arr.isArray() && arr.size() > 0) {
            String u = arr.get(0).path("url").asText();
            if (!u.isEmpty()) return u;
        }
        return null;
    }

    /**
     * INIT_STATE 顶层有多个 key（视频帖 2 个、图集帖 5 个），且 key 名是 Caesar -1 加密的乱码。
     * 但 value 里的 photo / atlas 子节点是明文。不用解密 key，直接遍历找含 photo 的 value 整体返回，
     * 这样图集分支能拿到 photo 兄弟节点 atlas。
     */
    private JsonNode findContentContainer(String html) throws Exception {
        Matcher m = Pattern.compile(
                "window\\.INIT_STATE\\s*=\\s*(\\{.*?\\})\\s*;?\\s*</script>",
                Pattern.DOTALL).matcher(html);
        if (!m.find()) return null;
        JsonNode root = mapper.readTree(m.group(1));
        Iterator<JsonNode> it = root.elements();
        while (it.hasNext()) {
            JsonNode val = it.next();
            JsonNode photo = val.path("photo");
            if (!photo.isMissingNode() && !photo.isNull() && photo.size() > 0) {
                return val;
            }
        }
        return null;
    }

    private String fetchHtml(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Upgrade-Insecure-Requests", "1")
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("请求失败: HTTP " + response.code());
            }
            return response.body() == null ? "" : response.body().string();
        }
    }

    private String extractUrl(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("(https?://\\S+)").matcher(text);
        if (m.find()) return m.group(1).replaceAll("[。！？，]$", "");
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
        String FALLBACK = "https://v.kuaishou.com/n7VXyJZ1 30张高清live实况素材 拿了留痕 \"素材分享 \"原创素材 \"百分摄影大赏 该作品在快手被播放过1,685.1万次，点击链接，打开【快手】直接观看！";
        String shareText = (args != null && args.length > 0 && !args[0].isBlank()) ? args[0] : FALLBACK;

        KuaishouMediaService svc = new KuaishouMediaService();
        svc.userAgent = DEFAULT_UA;

        try {
            KuaishouMediaVO vo = svc.extractMedia(shareText);
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
            System.err.println("  - 链接失效 → 直接用 m.gifshow.com/fw/photo/{id} 重试");
            System.err.println("  - 快手改版 → 看 /tmp/kuaishou_dump.html 里 window.INIT_STATE 还在不在");
        }
    }
}
