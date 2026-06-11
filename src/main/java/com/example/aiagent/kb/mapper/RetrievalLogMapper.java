package com.example.aiagent.kb.mapper;

import com.example.aiagent.kb.entity.RetrievalLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 检索日志 MyBatis Mapper
 */
@Mapper
public interface RetrievalLogMapper {

    List<RetrievalLog> findByTenantId(@Param("tenantId") String tenantId,
                                       @Param("offset") int offset,
                                       @Param("limit") int limit);

    List<RetrievalLog> findByKbIdAndCreatedAtAfter(@Param("kbId") Long kbId,
                                                    @Param("since") Instant since);

    List<Map<String, Object>> countGroupByAnswerType(@Param("tenantId") String tenantId,
                                                      @Param("kbId") Long kbId,
                                                      @Param("since") Instant since);

    long countByTenantIdAndKbIdAndCreatedAtAfter(@Param("tenantId") String tenantId,
                                                  @Param("kbId") Long kbId,
                                                  @Param("since") Instant since);

    List<RetrievalLog> findRecentByKbId(@Param("tenantId") String tenantId,
                                         @Param("kbId") Long kbId,
                                         @Param("limit") int limit);

    void insert(RetrievalLog log);
}
