-- 부동산 MCP 도구 6종 중 V2에서 누락된 5종을 백엔드 mcp_tool registry에 적재한다.
-- 모든 도구는 MCP 서버의 MolitRealEstateInput(region, deal_ymd) 계약을 공유한다.

WITH specs(name, description) AS (
    VALUES
        (
            'search_apt_rent',
            '국토교통부 아파트 전월세 실거래가 조회. region(시군구명 또는 LAWD_CD 5자리)과 deal_ymd(YYYYMM) 를 받아 통계 요약 + 보증금 내림차순 상위 20건 trades 를 공통 스키마로 반환한다.'
        ),
        (
            'search_offi_trade',
            '국토교통부 오피스텔 매매 실거래가 조회. region(시군구명 또는 LAWD_CD 5자리)과 deal_ymd(YYYYMM) 를 받아 통계 요약 + 가격 내림차순 상위 20건 trades 를 공통 스키마로 반환한다.'
        ),
        (
            'search_offi_rent',
            '국토교통부 오피스텔 전월세 실거래가 조회. region(시군구명 또는 LAWD_CD 5자리)과 deal_ymd(YYYYMM) 를 받아 통계 요약 + 보증금 내림차순 상위 20건 trades 를 공통 스키마로 반환한다.'
        ),
        (
            'search_rh_trade',
            '국토교통부 연립다세대 매매 실거래가 조회. region(시군구명 또는 LAWD_CD 5자리)과 deal_ymd(YYYYMM) 를 받아 통계 요약 + 가격 내림차순 상위 20건 trades 를 공통 스키마로 반환한다.'
        ),
        (
            'search_rh_rent',
            '국토교통부 연립다세대 전월세 실거래가 조회. region(시군구명 또는 LAWD_CD 5자리)과 deal_ymd(YYYYMM) 를 받아 통계 요약 + 보증금 내림차순 상위 20건 trades 를 공통 스키마로 반환한다.'
        )
)
INSERT INTO mcp_tool (server_id, domain_id, name, description, input_schema)
SELECT
    (SELECT id FROM mcp_server WHERE name = 'monitoring-mcp'),
    (SELECT id FROM domain WHERE name = 'real-estate'),
    specs.name,
    specs.description,
    (SELECT input_schema FROM mcp_tool WHERE name = 'search_house_price' LIMIT 1)
FROM specs
ON CONFLICT (server_id, name) DO UPDATE SET
    domain_id = EXCLUDED.domain_id,
    description = EXCLUDED.description,
    input_schema = EXCLUDED.input_schema;

SELECT setval(
    pg_get_serial_sequence('mcp_tool', 'id'),
    GREATEST((SELECT COALESCE(MAX(id), 0) FROM mcp_tool), 1),
    true
);
