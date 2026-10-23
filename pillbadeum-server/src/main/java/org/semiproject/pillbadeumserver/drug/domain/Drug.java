package org.semiproject.pillbadeumserver.drug.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.Objects;

/*  의약품 기본 정보 (drug 테이블)
 *  ----------------------------------------------------------------------------------
 *  공공데이터 '의약품 낱알식별 정보' 를 저장해 두는 테이블이다. PK 는 품목기준코드(item_seq).
 *  my_drug, dur_warning, dur_interaction 이 이 테이블을 FK 로 참조하므로
 *  내 약을 저장하기 전에 반드시 drug 행이 있어야 한다. (없으면 DrugSyncService 가 낱알식별을 조회해서 저장)
 *  ----------------------------------------------------------------------------------
 *  - 효능 · 용법 · 주의사항 글은 이 테이블에 없다. 근거 출처(guide)는 e약은요를 실시간으로 조회한다.
 *  - 테이블은 sql/01_PILLBADEUM_DB_SCRIPT.sql 로 만든다. (ddl-auto: none) 컬럼을 바꾸려면 팀 검토가 먼저다.
 *  - 팀 결정으로 DB 컬럼명 오타 matrial 을 material 로 바로잡았다. (기존 DB는 sql/03_MEDICATION_UPDATE.sql 적용)
 *    (낱알식별에는 성분명이 없어 DrugSyncService 가 허가정보 API 에서 채운다)
 *  - etc_otc 는 CHECK 제약으로 'ETC' 또는 'OTC' 만 저장할 수 있다.
 * */
@Entity
@Table(name = "drug")
public class Drug {

    public static final String ETC = "ETC";   // 전문의약품
    public static final String OTC = "OTC";   // 일반의약품

    @Id
    @Column(name = "item_seq", length = 100, nullable = false)
    private String itemSeq;             // 품목기준코드 (ITEM_SEQ)

    @Column(name = "name", length = 50, nullable = false)
    private String name;                // 제품명 (ITEM_NAME)

    @Column(name = "maker", length = 50, nullable = false)
    private String maker;               // 업체명 (ENTP_NAME)

    @Column(name = "etc_otc", length = 10, nullable = false)
    private String etcOtc;              // ETC / OTC

    @Column(name = "class_name", length = 50, nullable = false)
    private String className;           // 분류명

    @Column(name = "image_url", length = 255)
    private String imageUrl;            // 이미지가 없는 약도 있음

    @Column(name = "print_front", length = 50)
    private String printFront;          // 앞면 식별 문자

    @Column(name = "print_back", length = 50)
    private String printBack;           // 뒷면 식별 문자

    @Column(name = "shape", length = 50, nullable = false)
    private String shape;               // 모양

    @Column(name = "color", length = 50, nullable = false)
    private String color;               // 색상

    @Column(name = "line_front", length = 10)
    private String lineFront;           // 앞면 분할선

    @Column(name = "line_back", length = 10)
    private String lineBack;            // 뒷면 분할선

    @Column(name = "form", length = 10, nullable = false)
    private String form;                // 제형 (정제, 연질캡슐, 경질캡슐 등)

    @Column(name = "material", columnDefinition = "TEXT", nullable = false)
    private String material;            // 성분명 (예: 암로디핀베실산염)

    @Column(name = "synced_at", nullable = false)
    private LocalDateTime syncedAt;     // 공공데이터에서 가져온 시각 (근거 출처의 조회 시각으로도 보여줌)

    @Column(name = "metadata_source", nullable = false, length = 20)
    private String metadataSource = "GRAIN";

    @Column(name = "cancelled")
    private Boolean cancelled;

    /** 신규 허가/낱알 자료의 출처만 기록하며 과거 출처를 추측하지 않음. */
    public void recordMetadataSource(String source, Boolean cancellation) {
        if (!java.util.Set.of("UNKNOWN", "GRAIN", "PERMIT", "MIXED").contains(source))
            throw new IllegalArgumentException("의약품 자료 출처를 확인해주세요.");
        metadataSource = source;
        if (cancellation != null) cancelled = cancellation;
    }

    public String getMetadataSource() { return metadataSource; }
    public Boolean getCancelled() { return cancelled; }
    public String metadataSourceName() {
        return switch (metadataSource) {
            case "GRAIN" -> "식품의약품안전처 의약품 낱알식별 정보";
            case "PERMIT" -> "식품의약품안전처 의약품 제품 허가정보";
            case "MIXED" -> "식품의약품안전처 의약품 낱알식별·제품 허가정보";
            default -> "식품의약품안전처 의약품 정보(기존 캐시·출처 미확인)";
        };
    }

    // JPA 전용 기본 생성자
    protected Drug() {
    }

    // 공공데이터 조회 결과로 새 약 정보를 만든다.
    public static Drug create(String itemSeq, String name, String maker, String etcOtc, String className,
                              String imageUrl, String printFront, String printBack, String shape, String color,
                              String lineFront, String lineBack, String form, String material,
                              LocalDateTime syncedAt) {

        Drug drug = new Drug();
        drug.itemSeq = Objects.requireNonNull(itemSeq, "itemSeq");
        drug.sync(name, maker, etcOtc, className, imageUrl, printFront, printBack, shape, color,
                lineFront, lineBack, form, material, syncedAt);
        return drug;
    }

    // 이미 저장된 약을 최신 공공데이터 값으로 갱신한다. (upsert 의 update 쪽)
    // item_seq(PK) 는 바꾸지 않는다.
    public void sync(String name, String maker, String etcOtc, String className,
                     String imageUrl, String printFront, String printBack, String shape, String color,
                     String lineFront, String lineBack, String form, String material,
                     LocalDateTime syncedAt) {

        if (!ETC.equals(etcOtc) && !OTC.equals(etcOtc)) {
            throw new IllegalArgumentException("etcOtc 는 ETC 또는 OTC 만 가능합니다.");
        }

        this.name = name;
        this.maker = maker;
        this.etcOtc = etcOtc;
        this.className = className;
        this.imageUrl = imageUrl;
        this.printFront = printFront;
        this.printBack = printBack;
        this.shape = shape;
        this.color = color;
        this.lineFront = lineFront;
        this.lineBack = lineBack;
        this.form = form;
        this.material = DrugIngredient.forStorage(material);
        this.syncedAt = Objects.requireNonNull(syncedAt, "syncedAt");
    }

    public String getItemSeq() {
        return itemSeq;
    }

    public String getName() {
        return name;
    }

    public String getMaker() {
        return maker;
    }

    public String getEtcOtc() {
        return etcOtc;
    }

    public String getClassName() {
        return className;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public String getPrintFront() {
        return printFront;
    }

    public String getPrintBack() {
        return printBack;
    }

    public String getShape() {
        return shape;
    }

    public String getColor() {
        return color;
    }

    public String getLineFront() {
        return lineFront;
    }

    public String getLineBack() {
        return lineBack;
    }

    public String getForm() {
        return form;
    }

    public String getMaterial() {
        return material;
    }

    public LocalDateTime getSyncedAt() {
        return syncedAt;
    }
}
