package com.example.demo.tools;


import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.example.demo.entity.po.Course;
import com.example.demo.entity.po.CourseReservation;
import com.example.demo.entity.po.School;
import com.example.demo.entity.query.CourseQuery;
import com.example.demo.exception.BusinessException;
import com.example.demo.service.ICourseReservationService;
import com.example.demo.service.ICourseService;
import com.example.demo.service.ISchoolService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;

@RequiredArgsConstructor
@Component
public class CourseTools {

    // 允许作为 ORDER BY 的字段白名单，防止通过 sort.field 注入 SQL
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("price", "duration");

    private final ICourseService courseService;
    private final ISchoolService schoolService;
    private final ICourseReservationService reservationService;


    @Tool(description = "根据条件查询课程")
    public List<Course> queryCourses(@ToolParam(description = "查询的条件",required = true) CourseQuery query){
       if(query == null){
           return List.of();
           //return courseService.list();//当用户的输入为空的时候返回所有的结果
       }
        QueryChainWrapper<Course>wrapper=courseService.query()
                .eq(query.getType()!=null, "type",query.getType())//type="编程"
                .le(query.getEdu()!=null, "edu",query.getEdu());//edu<=2
        if(query.getSorts()!=null&&!query.getSorts().isEmpty()){
            for(CourseQuery.Sort sort:query.getSorts()){
                // 先校验字段是否在白名单中，再拼入 ORDER BY，避免 SQL 注入
                if(!StringUtils.hasText(sort.getField()) || !ALLOWED_SORT_FIELDS.contains(sort.getField())){
                    continue;
                }
                wrapper.orderBy(true, sort.getAsc()!=null&&sort.getAsc(), sort.getField());//根据提示的格式我们可以发现要求是boolean、升降序、要进行排序的文件
            }
        }

        return wrapper.list();
    }

    @Tool(description = "查询所有的校区")
    public List<School>querySchool(){
        return schoolService.list();
    }

    @Tool(description = "生成预约单，返回预约单号", name = "CreateCourseReservation")
    @Transactional(rollbackFor = Exception.class)
    public Integer createCourseReservation(
            @ToolParam(description = "预约课程") String course,
            @ToolParam(description = "预约校区") String school,
            @ToolParam(description = "学生姓名") String studentName,
            @ToolParam(description = "联系方式") String contactInfo,
            @ToolParam(description = "备注",required = false) String remark){
        // 基础参数校验：课程、校区、姓名、联系方式均不能为空
        if (!StringUtils.hasText(course) || !StringUtils.hasText(school)
                || !StringUtils.hasText(studentName) || !StringUtils.hasText(contactInfo)) {
            throw new BusinessException("预约信息不完整，请提供课程、校区、姓名和联系方式");
        }
        CourseReservation reservation = new CourseReservation();
        reservation.setCourse(course);
        reservation.setSchool(school);
        reservation.setStudentName(studentName);
        reservation.setContactInfo(contactInfo);
        reservation.setRemark(remark);
        reservationService.save(reservation);

        return reservation.getId();
    }
}
