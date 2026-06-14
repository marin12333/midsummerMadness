package com.madness.mqmremovemark.controller;

import com.madness.mqmremovemark.common.BusinessException;
import com.madness.mqmremovemark.common.ErrorCode;
import com.madness.mqmremovemark.model.KuaishouMediaRequest;
import com.madness.mqmremovemark.model.KuaishouMediaVO;
import com.madness.mqmremovemark.model.Response;
import com.madness.mqmremovemark.service.KuaishouMediaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/kuaishou")
public class KuaishouController {

    @Autowired
    private KuaishouMediaService kuaishouMediaService;

    /**
     * 获取快手无水印媒体资源
     */
    @PostMapping("/extract")
    public Response<KuaishouMediaVO> extractMedia(@RequestBody KuaishouMediaRequest request) {
        if (request.getUrl() == null || request.getUrl().trim().isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR);
        }
        return Response.success(kuaishouMediaService.extractMedia(request.getUrl()));
    }
}
