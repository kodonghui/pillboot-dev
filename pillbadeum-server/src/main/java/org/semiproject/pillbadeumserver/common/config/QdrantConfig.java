package org.semiproject.pillbadeumserver.common.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/* 설명. Qdrant(벡터 검색) 연결 (CHAT-004 임베딩 저장, CHAT-006 비슷한 과거 요약 검색)
 *  ----------------------------------------------------------------------------------
 *  - 기본은 꺼짐(qdrant.enabled=false). 꺼져 있으면 QdrantClient 빈을 만들지 않고 연결도 하지 않는다.
 *  - 켜는 법: Docker로 Qdrant를 띄우고(compose.qdrant.yaml) 개인 .env에 QDRANT_ENABLED=true.
 *  - Qdrant를 쓰는 코드는 꺼져 있을 때도 서버가 뜨도록 ObjectProvider<QdrantClient>로 받는다.
 *  - 컬렉션 생성 · 저장 · 검색은 대화 기능을 만들 때 추가한다. (벡터 크기는 openai.model.embedding 기준)
 * */
@Configuration
public class QdrantConfig {

    private static final Logger log = LoggerFactory.getLogger(QdrantConfig.class);

    public QdrantConfig(@Value("${qdrant.enabled}") boolean enabled) {
        if (!enabled) {
            log.info("Qdrant 꺼짐(qdrant.enabled=false): 벡터 저장 · 검색은 건너뜁니다.");
        }
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "qdrant.enabled", havingValue = "true")
    public QdrantClient qdrantClient(@Value("${qdrant.host}") String host,
                                     @Value("${qdrant.port}") int port) {
        log.info("Qdrant 연결 준비: {}:{} (gRPC)", host, port);
        return new QdrantClient(QdrantGrpcClient.newBuilder(host, port, false).build());
    }
}
