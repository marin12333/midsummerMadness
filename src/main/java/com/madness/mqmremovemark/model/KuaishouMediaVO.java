package com.madness.mqmremovemark.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KuaishouMediaVO {

    private String title;

    private String author;

    /** "video" 或 "image" */
    private String type;

    private List<String> images;

    private List<String> videos;

    private String cover;
}
