package com.college.attendance.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Backend-paginated response for the developer account list.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PagedAccountsDTO {

    private List<UserProfileDTO> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private boolean first;
    private boolean last;
}
