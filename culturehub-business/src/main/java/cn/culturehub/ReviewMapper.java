package cn.culturehub;

@org.apache.ibatis.annotations.Mapper
public interface ReviewMapper
    extends com.baomidou.mybatisplus.core.mapper.BaseMapper<ReviewMapper.Entity> {
  @com.baomidou.mybatisplus.annotation.TableName("review")
  @com.fasterxml.jackson.databind.annotation.JsonNaming(
      com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
  class Entity {
    @com.baomidou.mybatisplus.annotation.TableId(
        type = com.baomidou.mybatisplus.annotation.IdType.AUTO)
    public Long id;

    public Long userId;
    public Long sessionId;
    public Long participationId;
    public Integer rating;
    public String content;
    public java.time.LocalDateTime createdAt;
    public java.time.LocalDateTime updatedAt;

    public Long getId() {
      return id;
    }

    public Long getUserId() {
      return userId;
    }

    public Long getSessionId() {
      return sessionId;
    }

    public Long getParticipationId() {
      return participationId;
    }

    public Integer getRating() {
      return rating;
    }

    public String getContent() {
      return content;
    }

    public java.time.LocalDateTime getCreatedAt() {
      return createdAt;
    }

    public java.time.LocalDateTime getUpdatedAt() {
      return updatedAt;
    }
  }
}
