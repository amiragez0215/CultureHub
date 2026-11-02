package cn.culturehub;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface VenueMapper extends BaseMapper<VenueMapper.Venue> {
  @TableName("venue")
  class Venue {
    @com.baomidou.mybatisplus.annotation.TableId(
        type = com.baomidou.mybatisplus.annotation.IdType.AUTO)
    public Long id;

    public String name;
    public String address;

    @com.baomidou.mybatisplus.annotation.TableField(
        updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    public String introduction;

    public String category;
    public String openingHours;
    public String status;

    public Long getId() {
      return id;
    }

    public void setId(Long v) {
      id = v;
    }

    public String getName() {
      return name;
    }

    public void setName(String v) {
      name = v;
    }

    public String getAddress() {
      return address;
    }

    public void setAddress(String v) {
      address = v;
    }

    public String getIntroduction() {
      return introduction;
    }

    public String getCategory() {
      return category;
    }

    public void setCategory(String v) {
      category = v;
    }

    public void setIntroduction(String v) {
      introduction = v;
    }

    public String getOpeningHours() {
      return openingHours;
    }

    public void setOpeningHours(String v) {
      openingHours = v;
    }

    public String getStatus() {
      return status;
    }

    public void setStatus(String v) {
      status = v;
    }
  }
}
