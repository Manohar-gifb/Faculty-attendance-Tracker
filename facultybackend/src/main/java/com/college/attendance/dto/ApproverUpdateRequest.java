package com.college.attendance.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApproverUpdateRequest {

    private Long leaveApproverId;
    private Long secondaryApproverId;
}
