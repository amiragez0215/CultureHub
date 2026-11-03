package cn.culturehub;

@org.apache.ibatis.annotations.Mapper
public interface ActivityMapper
    extends com.baomidou.mybatisplus.core.mapper.BaseMapper<ActivityMapper.Entity> {
  @com.baomidou.mybatisplus.annotation.TableName("activity")
  @com.fasterxml.jackson.databind.annotation.JsonNaming(
      com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
  class Entity {
    @com.baomidou.mybatisplus.annotation.TableId(
        type = com.baomidou.mybatisplus.annotation.IdType.AUTO)
    public Long id;

    public Long venueId;
    public Long createdBy;
    public String title;
    public String description;
    public String category;
    public String audience;
    public String plan;
    public String notice;
    public String copywriting;
    public String status;
    public java.time.LocalDateTime createdAt;

    public Long getId() {
      return id;
    }

    public Long getVenueId() {
      return venueId;
    }

    public Long getCreatedBy() {
      return createdBy;
    }

    public String getTitle() {
      return title;
    }

    public String getDescription() {
      return description;
    }

    public String getCategory() {
      return category;
    }

    public String getAudience() {
      return audience;
    }

    public String getPlan() {
      return plan;
    }

    public String getNotice() {
      return notice;
    }

    public String getCopywriting() {
      return copywriting;
    }

    public String getStatus() {
      return status;
    }

    public java.time.LocalDateTime getCreatedAt() {
      return createdAt;
    }
  }
}
