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
public class ExtractionResult {
    List<String> imageUrls;
    List<String> livePhotoUrls;
    List<String> videoUrls;
    List<String> videoCoverUrls;
    String message;
}