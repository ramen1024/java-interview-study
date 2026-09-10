package com.jis.review.config;

import com.jis.review.ReviewProperties;
import com.jis.review.fsrs.FsrsParameters;
import com.jis.review.fsrs.FsrsScheduler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ReviewConfig {

    /**
     * 调度器按配置构造一次即可复用——它没有可变状态，
     * 内部只有从参数推导出来的 decay / factor 两个常量。
     */
    @Bean
    public FsrsScheduler fsrsScheduler(ReviewProperties properties) {
        return new FsrsScheduler(
                FsrsParameters.DEFAULT,
                properties.desiredRetention(),
                properties.maximumInterval(),
                properties.fuzzingEnabled()
        );
    }
}
