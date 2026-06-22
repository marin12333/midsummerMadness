package com.madness.mqmremovemark.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WeiboMediaVO {

    private String title;

    private String author;

    /** "video" 或 "image" */
    private String type;

    private List<String> images;

    /** 实况图（live photo）对应的小视频片段，与 images 一一对应中能取到的那部分 */
    private List<String> livePhotos;

    private List<String> videos;

    private String cover;
}
