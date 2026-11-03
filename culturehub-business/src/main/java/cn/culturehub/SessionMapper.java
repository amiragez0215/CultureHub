package cn.culturehub;

@org.apache.ibatis.annotations.Mapper
public interface SessionMapper
    extends com.baomidou.mybatisplus.core.mapper.BaseMapper<SessionMapper.Entity> {
  @com.baomidou.mybatisplus.annotation.TableName("activity_session")
  @com.fasterxml.jackson.databind.annotation.JsonNaming(
      com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
  class Entity {
    @com.baomidou.mybatisplus.annotation.TableId(
        type = com.baomidou.mybatisplus.annotation.IdType.AUTO)
    public Long id;

    public Long activityId;
    public Long lastWaitlistSeq;
    public Integer capacity;
    public Integer occupiedCount;
    public String status;
    public java.time.LocalDateTime registrationStartAt;
    public java.time.LocalDateTime registrationEndAt;
    public java.time.LocalDateTime startAt;
    public java.time.LocalDateTime endAt;
    public java.time.LocalDateTime checkinOpenAt;
    public java.time.LocalDateTime checkinCloseAt;

    public Long getId() {
      return id;
    }

    public Long getActivityId() {
      return activityId;
    }

    public Long getLastWaitlistSeq() {
      return lastWaitlistSeq;
    }

    public Integer getCapacity() {
      return capacity;
    }

    public Integer getOccupiedCount() {
      return occupiedCount;
    }

    public String getStatus() {
      return status;
    }

    public java.time.LocalDateTime getRegistrationStartAt() {
      return registrationStartAt;
    }

    public java.time.LocalDateTime getRegistrationEndAt() {
      return registrationEndAt;
    }

    public java.time.LocalDateTime getStartAt() {
      return startAt;
    }

    public java.time.LocalDateTime getEndAt() {
      return endAt;
    }

    public java.time.LocalDateTime getCheckinOpenAt() {
      return checkinOpenAt;
    }

    public java.time.LocalDateTime getCheckinCloseAt() {
      return checkinCloseAt;
    }
  }
}
