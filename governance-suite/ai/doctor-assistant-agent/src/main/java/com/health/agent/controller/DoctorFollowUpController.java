package com.health.agent.controller;

import com.alibaba.fastjson2.JSON;
import com.health.agent.model.PendingTask;
import com.health.agent.service.HighRiskFollowUpService;
import com.health.agent.service.PendingTaskService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/doctor/follow-up-tasks")
@RequiredArgsConstructor
public class DoctorFollowUpController {
    private final PendingTaskService tasks;
    private final HighRiskFollowUpService reminders;

    public record Task(String id, String customerId, String conversationId, String patientMessage, String reminder,
                       String status, String reviewComment, LocalDateTime createdAt, LocalDateTime reviewedAt) {}
    public record Review(@Size(max = 1000) String comment) {}

    @GetMapping
    public ResponseEntity<List<Task>> pending(HttpServletRequest caller) {
        String doctor = doctor(caller);
        return response(tasks.listPendingByDoctor(doctor).stream()
                .filter(task -> HighRiskFollowUpService.TOOL.equals(task.getToolName()) && doctor.equals(task.getDoctorId()))
                .map(DoctorFollowUpController::detail).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Task> get(HttpServletRequest caller, @PathVariable long id) {
        return response(detail(requireTask(id, doctor(caller))));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<Task> approve(HttpServletRequest caller, @PathVariable long id, @Valid @RequestBody Review review) {
        return decide(caller, id, review, true);
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Task> reject(HttpServletRequest caller, @PathVariable long id, @Valid @RequestBody Review review) {
        return decide(caller, id, review, false);
    }

    private ResponseEntity<Task> decide(HttpServletRequest caller, long id, Review review, boolean approve) {
        String doctor = doctor(caller);
        requireTask(id, doctor);
        String comment = review.comment() == null ? null : review.comment().strip();
        reminders.review(id, doctor, approve, comment);
        return response(detail(requireTask(id, doctor)));
    }

    private PendingTask requireTask(long id, String doctor) {
        PendingTask task = tasks.getById(id);
        if (task == null || !HighRiskFollowUpService.TOOL.equals(task.getToolName())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "随访任务不存在");
        }
        if (!doctor.equals(task.getDoctorId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅责任医生可查看此随访");
        }
        return task;
    }

    private static Task detail(PendingTask task) {
        var input = JSON.parseObject(task.getInputJson());
        return new Task(task.getId().toString(), task.getCustomerId(), input.getString("conversationId"),
                input.getString("patientMessage"), input.getString("reminder"), task.getStatus(),
                task.getReviewComment(), task.getCreatedAt(), task.getReviewedAt());
    }

    private static String doctor(HttpServletRequest caller) {
        return FollowUpReminderController.identity(caller, "DOCTOR");
    }

    private static <T> ResponseEntity<T> response(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
