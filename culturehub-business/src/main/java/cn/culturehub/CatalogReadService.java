package cn.culturehub;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.*;
import org.springframework.stereotype.Service;

/** Explicit read service; write state machines stay in their original transaction services. */
@Service
public class CatalogReadService {
  final ActivityMapper activities;
  final SessionMapper sessions;
  final ReviewMapper reviews;

  public CatalogReadService(
      ActivityMapper activities, SessionMapper sessions, ReviewMapper reviews) {
    this.activities = activities;
    this.sessions = sessions;
    this.reviews = reviews;
  }

  public ActivityMapper.Entity published(long id) {
    var row =
        activities.selectOne(
            Wrappers.<ActivityMapper.Entity>lambdaQuery()
                .eq(ActivityMapper.Entity::getId, id)
                .eq(ActivityMapper.Entity::getStatus, "PUBLISHED"));
    if (row == null) throw new Problem("NOT_FOUND", "Activity not found", 404);
    return row;
  }

  public List<SessionMapper.Entity> sessions(long activity) {
    return sessions.selectList(
        Wrappers.<SessionMapper.Entity>lambdaQuery()
            .eq(SessionMapper.Entity::getActivityId, activity)
            .orderByAsc(SessionMapper.Entity::getId));
  }

  public List<ReviewMapper.Entity> reviews(long session) {
    return reviews.selectList(
        Wrappers.<ReviewMapper.Entity>lambdaQuery()
            .eq(ReviewMapper.Entity::getSessionId, session)
            .orderByDesc(ReviewMapper.Entity::getId)
            .last("limit 100"));
  }
}
