package org.semiproject.pillbadeumserver.common.config;

import org.semiproject.pillbadeumserver.common.web.DuplicateRequestInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/* 설명. Spring MVC 공통 설정
 *  ----------------------------------------------------------------------------------
 *  1) 중복 요청 막기 인터셉터 등록 (COMMON-002)
 *  ----------------------------------------------------------------------------------
 *  참고. 업로드 이미지를 /uploads/** 로 '누구나' 열어 두는 설정은 일부러 넣지 않았다.
 *       처방전 · 약봉투 사진에는 이름, 병원, 복용 약 같은 민감 정보가 들어 있기 때문이다. (COMMON-003, 004)
 *       이미지는 이미지 담당이 만드는 API(본인 확인 후 FileStorage.loadAsResource())로만 내려준다.
 * */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final DuplicateRequestInterceptor duplicateRequestInterceptor;

    public WebConfig(DuplicateRequestInterceptor duplicateRequestInterceptor) {
        this.duplicateRequestInterceptor = duplicateRequestInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(duplicateRequestInterceptor).addPathPatterns("/api/**");
    }
}
