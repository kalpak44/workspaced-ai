package io.github.kalpak44.workspacedai.api.mail;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface MailSenderRepository extends JpaRepository<MailSenderEntity, String> {

  List<MailSenderEntity> findAllByOrderByPositionAsc();
}
