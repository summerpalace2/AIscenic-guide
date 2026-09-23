package com.ai.guide.domain.search.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BaiduSearchResult {
    private String title;
    private String snippet;
    private String url;
    private String source;
    private String category;
    private String thumbnail;
}
