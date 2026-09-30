package cn.court.mediation.repository;

import cn.court.mediation.entity.RescheduleProposal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RescheduleProposalRepository extends JpaRepository<RescheduleProposal, Long> {
    List<RescheduleProposal> findByCaseIdOrderByProposedAtDesc(Long caseId);

    Optional<RescheduleProposal> findFirstByCaseIdAndStatusOrderByProposedAtDesc(Long caseId, String status);

    List<RescheduleProposal> findByStatusOrderByProposedAtDesc(String status);
}
