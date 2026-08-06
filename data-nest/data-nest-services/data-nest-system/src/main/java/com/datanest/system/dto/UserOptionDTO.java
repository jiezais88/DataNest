package com.datanest.system.dto;

/**
 * 用户选择器选项（告警接收人，仅返回已填写邮箱的用户）。
 */
public record UserOptionDTO(Long id, String username, String email) {
}
