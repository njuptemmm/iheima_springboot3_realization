package com.example.demo.entity.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 学科表
 * </p>
 *
 * @author emmm
 * @since 2025-07-06
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("course")
public class Course implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 学科名称
     */
    @TableField("name")
    private String name;

    /**
     * 学历背景要求：0-无，1-初中，2-高中、3-大专、4-本科以上
     */
    @TableField("edu")
    private Integer edu;

    /**
     * 课程类型：编程、设计、自媒体、其它
     */
    @TableField("type")
    private String type;

    /**
     * 课程价格
     */
    @TableField("price")
    private Long price;

    /**
     * 学习时长，单位: 天
     */
    @TableField("duration")
    private Integer duration;


}
