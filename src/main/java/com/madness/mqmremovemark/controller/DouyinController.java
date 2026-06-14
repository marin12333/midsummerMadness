package com.madness.mqmremovemark.controller;

import com.madness.mqmremovemark.common.BusinessException;
import com.madness.mqmremovemark.common.ErrorCode;
import com.madness.mqmremovemark.model.DouyinMediaRequest;
import com.madness.mqmremovemark.model.DouyinMediaVO;
import com.madness.mqmremovemark.model.Response;
import com.madness.mqmremovemark.service.DouyinMediaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/douyin")
public class DouyinController {

    @Autowired
    private DouyinMediaService douyinMediaService;

    /**
     * 获取抖音无水印媒体资源
     */
    @PostMapping("/extract")
    public Response<DouyinMediaVO> extractMedia(@RequestBody DouyinMediaRequest request) {
        if (request.getUrl() == null || request.getUrl().trim().isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR);
        }
        return Response.success(douyinMediaService.extractMedia(request.getUrl()));
    }
}
