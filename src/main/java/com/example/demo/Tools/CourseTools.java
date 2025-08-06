package com.example.demo.Tools;


import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.example.demo.entity.po.Course;
import com.example.demo.entity.po.CourseReservation;
import com.example.demo.entity.po.School;
import com.example.demo.entity.query.CourseQuery;
import com.example.demo.service.ICourseReservationService;
import com.example.demo.service.ICourseService;
import com.example.demo.service.ISchoolService;
import com.example.demo.service.impl.CourseServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@RequiredArgsConstructor
@Component
public class CourseTools {

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
                wrapper.orderBy(true,sort.getAsc(),sort.getField());//根据提示的格式我们可以发现要求是boolean、升降序、要进行排序的文件
            }
        }

        return wrapper.list();
    }

    @Tool(description = "查询所有的校区")
    public List<School>querySchool(){
        return schoolService.list();
    }

    @Tool(description = "生成预约单，返回预约单号")
    public Integer CreateCourseReservation(
            @ToolParam(description = "预约课程") String course,
            @ToolParam(description = "预约校区") String school,
            @ToolParam(description = "学生姓名") String studentName,
            @ToolParam(description = "联系方式") String contactInfo,
            @ToolParam(description = "备注",required = false) String remark){
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
