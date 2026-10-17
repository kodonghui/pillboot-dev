package org.semiproject.pillbadeumserver.common.external;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.DefaultUriBuilderFactory;

import java.net.http.HttpClient;
import java.time.Duration;

/* 설명. 외부 API 를 부를 RestClient 를 '시간 제한을 걸어서' 만드는 도구 (COMMON-001)
 *  ----------------------------------------------------------------------------------
 *  시간 제한이 없으면 외부 서버가 멈췄을 때 우리 서버의 요청도 끝없이 기다린다.
 *  - connectTimeout : 상대 서버에 연결하기까지의 최대 시간
 *  - readTimeout    : 요청을 보내고 응답이 시작되기까지의 최대 시간
 *  시간을 넘기면 ExternalApiCaller 가 503(EXTERNAL_TIMEOUT) 로 바꿔 준다.
 *  ----------------------------------------------------------------------------------
 *  주소 인코딩 방식을 VALUES_ONLY 로 둔 이유
 *  공공데이터포털 서비스 키에는 '+', '/', '=' 가 들어 있다. 기본 방식은 이 글자를 그대로 보내서
 *  '+' 가 공백으로 바뀌고 "SERVICE_KEY_IS_NOT_REGISTERED_ERROR" 가 난다.
 *  VALUES_ONLY 는 uri("...?serviceKey={key}", 값) 처럼 '변수로 넘긴 값'을 빠짐없이 인코딩한다.
 *  ----------------------------------------------------------------------------------
 *  사용 예) RestClient client = RestClientFactory.builder("https://...", Duration.ofSeconds(2), Duration.ofSeconds(5)).build();
 * */
public final class RestClientFactory {

    private RestClientFactory() {
    }

    public static RestClient.Builder builder(String baseUrl, Duration connectTimeout, Duration readTimeout) {

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        requestFactory.setReadTimeout(readTimeout);

        DefaultUriBuilderFactory uriBuilderFactory = new DefaultUriBuilderFactory(baseUrl);
        uriBuilderFactory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.VALUES_ONLY);

        return RestClient.builder()
                .uriBuilderFactory(uriBuilderFactory)
                .requestFactory(requestFactory);
    }
}
