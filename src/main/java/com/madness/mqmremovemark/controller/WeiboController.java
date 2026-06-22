package com.madness.mqmremovemark.controller;

import com.madness.mqmremovemark.common.BusinessException;
import com.madness.mqmremovemark.common.ErrorCode;
import com.madness.mqmremovemark.model.Response;
import com.madness.mqmremovemark.model.WeiboMediaRequest;
import com.madness.mqmremovemark.model.WeiboMediaVO;
import com.madness.mqmremovemark.service.WeiboMediaService;
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
@RequestMapping("/api/weibo")
public class WeiboController {

    @Autowired
    private WeiboMediaService weiboMediaService;

    /**
     * 傻瓜版：传分享链接，直接返回「已经代理好、可直接打开/下载」的无水印链接。
     * 返回里的 images / livePhotos / videos / cover 每条都已包装成 /download 代理地址，
     * 前端拿到直接 <img>/<video>/下载即可，不用再自己拼防盗链代理。
     */
    @PostMapping("/extract")
    public Response<WeiboMediaVO> extractMedia(@RequestBody WeiboMediaRequest request) {
        if (request.getUrl() == null || request.getUrl().trim().isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR);
        }
        WeiboMediaVO vo = weiboMediaService.extractMedia(request.getUrl());
        return Response.success(wrapAsProxy(vo));
    }

    /**
     * 更傻瓜版：浏览器直接开 /api/weibo/parse?url=分享链接 就能看到解析结果（GET，无需 POST）。
     */
    @GetMapping("/parse")
    public Response<WeiboMediaVO> parse(@RequestParam("url") String url) {
        if (url == null || url.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR);
        }
        WeiboMediaVO vo = weiboMediaService.extractMedia(url);
        return Response.success(wrapAsProxy(vo));
    }

    /**
     * 媒体代理：微博图片/视频有防盗链，原始直链直接打开会 403。
     * /api/weibo/download?url={编码后的媒体地址} 由服务端带 Referer 代拉后原样流回。
     */
    @GetMapping("/download")
    public void download(@RequestParam("url") String url,
                         @RequestHeader(value = "Range", required = false) String range,
                         HttpServletResponse response) {
        weiboMediaService.proxyMedia(url, range, response);
    }

    /** 把 VO 里所有媒体直链替换成本服务的 /download 代理地址（自适应当前 host/port/域名） */
    private WeiboMediaVO wrapAsProxy(WeiboMediaVO vo) {
        String prefix = ServletUriComponentsBuilder.fromCurrentContextPath().toUriString()
                + "/api/weibo/download?url=";
        vo.setImages(toProxyList(vo.getImages(), prefix));
        vo.setLivePhotos(toProxyList(vo.getLivePhotos(), prefix));
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
