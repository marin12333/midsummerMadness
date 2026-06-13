package com.madness.mqmremovemark.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
/**
 * 统一接口返回对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class XhsMediaVO {
    /**
     * 所有图片链接（普通图 + Live静态图 + 视频封面）
     */
    private List<String> images;
    /**
     * 所有视频链接（笔记视频 + Live动态视频）
     */
    private List<String> videos;
}