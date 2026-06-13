package com.madness.mqmremovemark.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class XhsDownloader {
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();
    private static final ObjectMapper objectMapper = new ObjectMapper();
    /**
     * 主入口
     */
    public static ExtractionResult extractFromShareText(String rawShareText) {
        String cleanUrl = extractUrlFromText(rawShareText);
        if (cleanUrl == null || cleanUrl.isEmpty()) {
            return new ExtractionResult(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), "未在文本中检测到有效的小红书链接");
        }
        System.out.println("检测到链接: " + cleanUrl);
        return extractResources(cleanUrl);
    }
    /**
     * 核心逻辑
     */
    private static ExtractionResult extractResources(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Referer", "https://www.xiaohongshu.com/")
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String html = response.body();
            System.out.println("服务器响应状态码: " + response.statusCode());
            String jsonStr = extractInitialState(html);
            if (jsonStr == null) {
                return new ExtractionResult(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), "无法解析页面数据");
            }
            JsonNode root = objectMapper.readTree(jsonStr);
            JsonNode noteDetailMap = root.path("note").path("noteDetailMap");
            if (noteDetailMap.isEmpty()) {
                return new ExtractionResult(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), "笔记详情数据为空");
            }
            String firstNoteId = noteDetailMap.fieldNames().next();
            JsonNode noteData = noteDetailMap.path(firstNoteId).path("note");
            List<String> imageUrls = new ArrayList<>();
            List<String> livePhotoUrls = new ArrayList<>();
            List<String> videoUrls = new ArrayList<>();
            List<String> videoCoverUrls = new ArrayList<>();
            // --- 视频笔记处理 ---
            JsonNode videoNode = noteData.path("video");
            if (!videoNode.isMissingNode() && !videoNode.isNull()) {
                System.out.println("检测到笔记类型：视频笔记");
                JsonNode mediaStream = videoNode.path("media").path("stream").path("h264");
                if (mediaStream.isArray() && mediaStream.size() > 0) {
                    String masterUrl = mediaStream.get(0).path("masterUrl").asText();
                    if (!masterUrl.isEmpty()) videoUrls.add(masterUrl);
                }
                if (videoUrls.isEmpty()) {
                    String directUrl = videoNode.path("url").asText();
                    if (!directUrl.isEmpty()) videoUrls.add(directUrl);
                }
                String coverUrl = null;
                JsonNode coverNode = videoNode.path("cover");
                if (!coverNode.isMissingNode() && !coverNode.isNull()) {
                    if (coverNode.isTextual()) coverUrl = coverNode.asText();
                    else {
                        coverUrl = coverNode.path("urlDefault").asText();
                        if (coverUrl.isEmpty()) coverUrl = coverNode.path("url").asText();
                    }
                }
                if (coverUrl == null || coverUrl.isEmpty()) {
                    JsonNode imageList = noteData.path("imageList");
                    if (imageList.isArray() && imageList.size() > 0) {
                        coverUrl = imageList.get(0).path("urlDefault").asText();
                    }
                }
                if (coverUrl != null && !coverUrl.isEmpty()) {
                    String coverId = extractResourceId(coverUrl);
                    String coverPrefix = extractUrlPrefix(coverUrl);
                    if (coverId != null) {
                        String pathPrefix = coverPrefix.isEmpty() ? "" : coverPrefix + "/";
                        String finalCover = String.format("https://ci.xiaohongshu.com/%s%s?imageView2/2/w/0/format/jpg", pathPrefix, coverId);
                        videoCoverUrls.add(finalCover);
                    } else {
                        videoCoverUrls.add(coverUrl);
                    }
                }
            } else {
                // --- 图文/实况图处理 ---
                System.out.println("检测到笔记类型：图文/实况图笔记");
                JsonNode imageList = noteData.path("imageList");
                for (JsonNode imageItem : imageList) {
                    String urlDefault = imageItem.path("urlDefault").asText();
                    String resourceId = extractResourceId(urlDefault);
                    if (resourceId != null) {
                        // --- 核心修复：使用修正后的前缀提取逻辑 ---
                        String urlPrefix = extractUrlPrefix(urlDefault);
                        // 构造最终 URL
                        String pathPrefix = urlPrefix.isEmpty() ? "" : urlPrefix + "/";
                        String finalUrl = String.format("https://ci.xiaohongshu.com/%s%s?imageView2/2/w/0/format/jpg", pathPrefix, resourceId);
                        imageUrls.add(finalUrl);
                    }
                    // Live Photo 视频
                    JsonNode streamNode = imageItem.path("stream").path("h264");
                    if (streamNode.isArray() && streamNode.size() > 0) {
                        String masterUrl = streamNode.get(0).path("masterUrl").asText();
                        if (!masterUrl.isEmpty()) {
                            livePhotoUrls.add(masterUrl);
                        }
                    }
                }
            }
            return new ExtractionResult(imageUrls, livePhotoUrls, videoUrls, videoCoverUrls, "提取成功");
        } catch (Exception e) {
            e.printStackTrace();
            return new ExtractionResult(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), "请求处理异常: " + e.getMessage());
        }
    }
    /**
     * 最终修正版：提取资源路径前缀
     * 原理：获取文件名（路径最后一段）的上一级目录名
     * 例如：/date/hash/notes_uhdr/id -> 提取 "notes_uhdr"
     */
    private static String extractUrlPrefix(String url) {
        try {
            String path = URI.create(url).getPath();
            String[] parts = path.split("/");
            // 如果路径长度至少为2（即至少有 /id），我们检查倒数第二段
            // parts.length - 1 是文件名
            // parts.length - 2 是文件所在的目录名（即前缀）
            if (parts.length >= 2) {
                String potentialPrefix = parts[parts.length - 2];
                // 校验是否是已知的有效前缀
                if (potentialPrefix.equals("spectrum") ||
                        potentialPrefix.equals("notes_uhdr") ||
                        potentialPrefix.equals("notes_pre_post") ||
                        potentialPrefix.equals("note_pre_post")) {
                    return potentialPrefix;
                }
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }
    private static String extractUrlFromText(String text) {
        if (text == null) return null;
        Pattern pattern = Pattern.compile("(https?://\\S+)");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
    private static String extractInitialState(String html) {
        Pattern pattern = Pattern.compile("window\\.__INITIAL_STATE__\\s*=\\s*(\\{[\\s\\S]*?)(?:</script>|;)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(html);
        if (matcher.find()) {
            return matcher.group(1).replace("undefined", "null");
        }
        Pattern patternSsr = Pattern.compile("window\\.__INITIAL_SSR_STATE__\\s*=\\s*(\\{[\\s\\S]*?)(?:</script>|;)", Pattern.CASE_INSENSITIVE);
        Matcher matcherSsr = patternSsr.matcher(html);
        if (matcherSsr.find()) {
            return matcherSsr.group(1).replace("undefined", "null");
        }
        return null;
    }
    private static String extractResourceId(String url) {
        try {
            String path = URI.create(url).getPath();
            String lastSegment = path.substring(path.lastIndexOf('/') + 1);
            int endIndex = lastSegment.indexOf('!');
            if (endIndex == -1) endIndex = lastSegment.indexOf('?');
            if (endIndex != -1) return lastSegment.substring(0, endIndex);
            return lastSegment;
        } catch (Exception e) {
            return null;
        }
    }
    public static class ExtractionResult {
        private List<String> imageUrls;
        private List<String> livePhotoUrls;
        private List<String> videoUrls;
        private List<String> videoCoverUrls;
        private String message;
        public ExtractionResult(List<String> imageUrls, List<String> livePhotoUrls, List<String> videoUrls, List<String> videoCoverUrls, String message) {
            this.imageUrls = imageUrls;
            this.livePhotoUrls = livePhotoUrls;
            this.videoUrls = videoUrls;
            this.videoCoverUrls = videoCoverUrls;
            this.message = message;
        }
        public List<String> getImageUrls() { return imageUrls; }
        public List<String> getLivePhotoUrls() { return livePhotoUrls; }
        public List<String> getVideoUrls() { return videoUrls; }
        public List<String> getVideoCoverUrls() { return videoCoverUrls; }
        public String getMessage() { return message; }
    }
    public static void main(String[] args) {
        String mixShareText = "65 【普吉岛的夏天 - 今天超开心耶 | 小红书 - 你的生活兴趣社区】 😆 meepyD13i5j0yXJ 😆 https://www.xiaohongshu.com/discovery/item/69f97e400000000036002d3a?source=webshare&xhsshare=pc_web&xsec_token=CBBvtKVHV2IGQeh6OElVh82ThsTM5b9kT6cWyADH-Z7lA=&xsec_source=pc_share";
        System.out.println("正在处理原始分享文本...");
        ExtractionResult result = extractFromShareText(mixShareText);
        System.out.println("\n" + result.getMessage());
        if (result.getImageUrls().size() > 0) {
            System.out.println("--- 图片列表 (包含 UHDR 静态图) ---");
            for (int i = 0; i < result.getImageUrls().size(); i++) {
                String url = result.getImageUrls().get(i);
                System.out.println((i + 1) + ": " + url);
                if (url.contains("notes_uhdr")) {
                    System.out.println("   -> [UHDR 高清图]");
                }
            }
        }
        if (result.getLivePhotoUrls().size() > 0) {
            System.out.println("\n--- Live Photo：动态视频部分 ---");
            result.getLivePhotoUrls().forEach(System.out::println);
        }
    }
}