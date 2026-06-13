package com.madness.mqmremovemark.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.madness.mqmremovemark.common.BusinessException;
import com.madness.mqmremovemark.common.ErrorCode;
import com.madness.mqmremovemark.model.ExtractionResult;
import com.madness.mqmremovemark.model.XhsMediaVO;
import org.springframework.stereotype.Service;

import java.net.BindException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class XhsMediaService {
    // 使用 Java 11+ HttpClient
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();
    private static final ObjectMapper objectMapper = new ObjectMapper();
    /**
     * 核心业务方法：提取并合并所有媒体资源
     */
    public XhsMediaVO extractAllMedia(String rawShareText) {
        // 1. 提取URL
        String cleanUrl = extractUrlFromText(rawShareText);
        if (cleanUrl == null || cleanUrl.isEmpty()) {
            return null;
        }
        // 2. 请求解析
        ExtractionResult result = extractResources(cleanUrl);
        // 3. 数据合并逻辑（符合您的需求：只返回两个集合）
        List<String> finalImages = new ArrayList<>();
        List<String> finalVideos = new ArrayList<>();
        // 合并图片：普通静态图 + 视频封面
        finalImages.addAll(result.getImageUrls());
        finalImages.addAll(result.getVideoCoverUrls());
        // 合并视频：实况图视频 + 视频笔记主视频
        finalVideos.addAll(result.getLivePhotoUrls());
        finalVideos.addAll(result.getVideoUrls());
        return new XhsMediaVO(finalImages, finalVideos);
    }
    /**
     * 内部解析逻辑
     */
    private ExtractionResult extractResources(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Referer", "https://www.xiaohongshu.com/")
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String jsonStr = extractInitialState(response.body());
            if (jsonStr == null) {
                throw new BusinessException(ErrorCode.DATA_PARSE_ERROR);
            }
            JsonNode root = objectMapper.readTree(jsonStr);
            JsonNode noteDetailMap = root.path("note").path("noteDetailMap");
            if (noteDetailMap.isEmpty()) {
                throw new BusinessException(ErrorCode.NOTE_EMPTY);
            }
            String firstNoteId = noteDetailMap.fieldNames().next();
            JsonNode noteData = noteDetailMap.path(firstNoteId).path("note");
            List<String> imageUrls = new ArrayList<>();
            List<String> livePhotoUrls = new ArrayList<>();
            List<String> videoUrls = new ArrayList<>();
            List<String> videoCoverUrls = new ArrayList<>();
            // --- 视频笔记 ---
            JsonNode videoNode = noteData.path("video");
            if (!videoNode.isMissingNode() && !videoNode.isNull()) {
                // 提取主视频
                JsonNode mediaStream = videoNode.path("media").path("stream").path("h264");
                if (mediaStream.isArray() && mediaStream.size() > 0) {
                    String masterUrl = mediaStream.get(0).path("masterUrl").asText();
                    if (!masterUrl.isEmpty()) videoUrls.add(masterUrl);
                }
                if (videoUrls.isEmpty()) {
                    String directUrl = videoNode.path("url").asText();
                    if (!directUrl.isEmpty()) videoUrls.add(directUrl);
                }
                // 提取封面
                String coverUrl = getCoverUrl(noteData);
                if (coverUrl != null) {
                    String coverId = extractResourceId(coverUrl);
                    String coverPrefix = extractUrlPrefix(coverUrl);
                    if (coverId != null) {
                        String pathPrefix = coverPrefix.isEmpty() ? "" : coverPrefix + "/";
                        videoCoverUrls.add(String.format("https://ci.xiaohongshu.com/%s%s?imageView2/2/w/0/format/jpg", pathPrefix, coverId));
                    }
                }
            } else {
                // --- 图文/实况图 ---
                JsonNode imageList = noteData.path("imageList");
                for (JsonNode imageItem : imageList) {
                    String urlDefault = imageItem.path("urlDefault").asText();
                    String resourceId = extractResourceId(urlDefault);
                    if (resourceId != null) {
                        // 核心逻辑：提取前缀（解决UHDR问题）
                        String urlPrefix = extractUrlPrefix(urlDefault);
                        String pathPrefix = urlPrefix.isEmpty() ? "" : urlPrefix + "/";
                        String finalUrl = String.format("https://ci.xiaohongshu.com/%s%s?imageView2/2/w/0/format/jpg", pathPrefix, resourceId);
                        imageUrls.add(finalUrl);
                    }
                    // 提取 Live 视频
                    JsonNode streamNode = imageItem.path("stream").path("h264");
                    if (streamNode.isArray() && streamNode.size() > 0) {
                        String masterUrl = streamNode.get(0).path("masterUrl").asText();
                        if (!masterUrl.isEmpty()) livePhotoUrls.add(masterUrl);
                    }
                }
            }
            return new ExtractionResult(imageUrls, livePhotoUrls, videoUrls, videoCoverUrls, "提取成功");
        } catch (Exception e) {
            e.printStackTrace();
            throw new BusinessException(ErrorCode.NETWORK_ERROR, "网络请求异常: " + e.getMessage());
        }
    }
    // 辅助：获取封面URL
    private String getCoverUrl(JsonNode noteData) {
        String coverUrl = null;
        JsonNode coverNode = noteData.path("cover");
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
        return coverUrl;
    }
    // 辅助：最终版前缀提取逻辑
    private String extractUrlPrefix(String url) {
        try {
            String path = URI.create(url).getPath();
            String[] parts = path.split("/");
            if (parts.length >= 2) {
                String potentialPrefix = parts[parts.length - 2];
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
    // 辅助：提取JSON
    private String extractInitialState(String html) {
        Pattern pattern = Pattern.compile("window\\.__INITIAL_STATE__\\s*=\\s*(\\{[\\s\\S]*?)(?:</script>|;)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(html);
        if (matcher.find()) return matcher.group(1).replace("undefined", "null");
        Pattern patternSsr = Pattern.compile("window\\.__INITIAL_SSR_STATE__\\s*=\\s*(\\{[\\s\\S]*?)(?:</script>|;)", Pattern.CASE_INSENSITIVE);
        Matcher matcherSsr = patternSsr.matcher(html);
        if (matcherSsr.find()) return matcherSsr.group(1).replace("undefined", "null");
        return null;
    }
    // 辅助：提取Resource ID
    private String extractResourceId(String url) {
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
    // 辅助：从文本提取URL
    private String extractUrlFromText(String text) {
        if (text == null) return null;
        Pattern pattern = Pattern.compile("(https?://\\S+)");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) return matcher.group(1);
        return null;
    }

}