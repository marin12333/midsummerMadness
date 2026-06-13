package com.madness.mqmremovemark.controller;

import com.madness.mqmremovemark.service.XhsMediaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/biz")
public class bizController {

    @Autowired
    private XhsMediaService xhsMediaService;
}
