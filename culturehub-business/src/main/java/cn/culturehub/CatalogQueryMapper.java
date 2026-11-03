package cn.culturehub;

import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.annotations.*;

/** Read models only. No locking or booking mutations are moved into this mapper. */
@Mapper
public interface CatalogQueryMapper {
  record Filter(
      boolean restricted,
      long user,
      String q,
      String category,
      long venue,
      String status,
      LocalDateTime from,
      LocalDateTime to) {}

  List<Map<String, Object>> activities(@Param("f") Filter filter, @Param("offset") int offset);

  long countActivities(@Param("f") Filter filter);

  List<ParticipationView> history(@Param("user") long user, @Param("offset") int offset);

  class ParticipationView {
    public Long id, session_id, activity_id, venue_id, queue_seq;
    public String status, title, venue_name;
    public LocalDateTime confirm_deadline, created_at, start_at, end_at, server_time;
  }
}
