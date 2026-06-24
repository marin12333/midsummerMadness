package com.madness.mqmremovemark.controller;

import com.madness.mqmremovemark.common.BusinessException;
import com.madness.mqmremovemark.common.ErrorCode;
import com.madness.mqmremovemark.model.BilibiliMediaRequest;
import com.madness.mqmremovemark.model.BilibiliMediaVO;
import com.madness.mqmremovemark.model.Response;
import com.madness.mqmremovemark.service.BilibiliMediaService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/bilibili")
public class BilibiliController {

    @Autowired
    private BilibiliMediaService bilibiliMediaService;

    /**
     * 傻瓜版：传B站分享链接（视频 BV/av/b23.tv、图文动态 t.bilibili.com/opus），
     * 直接返回「已代理好、可直接打开/下载」的无水印链接。
     */
    @PostMapping("/extract")
    public Response<BilibiliMediaVO> extractMedia(@RequestBody BilibiliMediaRequest request) {
        if (request.getUrl() == null || request.getUrl().trim().isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR);
        }
        return Response.success(wrapAsProxy(bilibiliMediaService.extractMedia(request.getUrl())));
    }

    /** 更傻瓜版：浏览器直接开 /api/bilibili/parse?url=分享链接 */
    @GetMapping("/parse")
    public Response<BilibiliMediaVO> parse(@RequestParam("url") String url) {
        if (url == null || url.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR);
        }
        return Response.success(wrapAsProxy(bilibiliMediaService.extractMedia(url)));
    }

    /**
     * 媒体代理：B站视频有防盗链，原始直链直接打开会 403。
     * /api/bilibili/download?url={编码后的媒体地址} 由服务端带 Referer 代拉后原样流回。
     */
    @GetMapping("/download")
    public void download(@RequestParam("url") String url,
                         @RequestHeader(value = "Range", required = false) String range,
                         HttpServletResponse response) {
        bilibiliMediaService.proxyMedia(url, range, response);
    }

    private BilibiliMediaVO wrapAsProxy(BilibiliMediaVO vo) {
        String prefix = ServletUriComponentsBuilder.fromCurrentContextPath().toUriString()
                + "/api/bilibili/download?url=";
        vo.setImages(toProxyList(vo.getImages(), prefix));
        vo.setVideos(toProxyList(vo.getVideos(), prefix));
        if (vo.getCover() != null && !vo.getCover().isEmpty()) {
            vo.setCover(prefix + URLEncoder.encode(vo.getCover(), StandardCharsets.UTF_8));
        }
        return vo;
    }

    private List<String> toProxyList(List<String> urls, String prefix) {
        List<String> out = new ArrayList<>();
        if (urls != null) {
            for (String u : urls) {
                out.add(prefix + URLEncoder.encode(u, StandardCharsets.UTF_8));
            }
        }
        return out;
    }
}
