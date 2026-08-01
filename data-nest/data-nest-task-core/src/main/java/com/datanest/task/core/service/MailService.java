package com.datanest.task.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Arrays;

/**
 * 邮件发送服务
 * 告警邮件发送，失败只记日志不影响主流程。
 */
@Service
public class MailService {

    private static final Logger logger = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender mailSender;

    @Value("${spring.mail.from:}")
    private String from;

    public MailService(ObjectProvider<JavaMailSender> mailSenderProvider) {
        this.mailSender = mailSenderProvider.getIfAvailable();
    }

    /**
     * 发送告警邮件。收件人以分号/逗号分隔。
     * 如果当前环境未配置 JavaMailSender，则只记日志不发送。
     */
    public void send(String recipients, String subject, String body) {
        if (mailSender == null) {
            logger.warn("未配置 JavaMailSender，告警邮件无法发送: subject={}", subject);
            return;
        }
        if (!StringUtils.hasText(recipients)) {
            return;
        }
        String[] tos = Arrays.stream(recipients.split("[;；,，]"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toArray(String[]::new);
        if (tos.length == 0) {
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(StringUtils.hasText(from) ? from : tos[0]);
        message.setTo(tos);
        message.setSubject(subject);
        message.setText(body);
        try {
            mailSender.send(message);
            logger.info("告警邮件已发送: recipients={}, subject={}", recipients, subject);
        } catch (MailException e) {
            logger.error("告警邮件发送失败: recipients={}, subject={}", recipients, subject, e);
        }
    }
}
