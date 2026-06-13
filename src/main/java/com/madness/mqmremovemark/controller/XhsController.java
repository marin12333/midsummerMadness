package com.madness.mqmremovemark.controller;

import com.madness.mqmremovemark.common.BusinessException;
import com.madness.mqmremovemark.common.ErrorCode;
import com.madness.mqmremovemark.model.Response;
import com.madness.mqmremovemark.model.XhsMediaRequest;
import com.madness.mqmremovemark.model.XhsMediaVO;
import com.madness.mqmremovemark.service.XhsMediaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/xhs")
public class XhsController {

    @Autowired
    private XhsMediaService xhsMediaService;

    /**
     * 获取无水印媒体资源
     */
    @PostMapping("/extract")
    public Response<XhsMediaVO> extractMedia(@RequestBody XhsMediaRequest request) {
        if (request.getUrl() == null || request.getUrl().trim().isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR);
        }
        return Response.success(xhsMediaService.extractAllMedia(request.getUrl()));
    }
}