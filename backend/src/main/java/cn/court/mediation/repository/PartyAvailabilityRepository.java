package cn.court.mediation.repository;

import cn.court.mediation.entity.PartyAvailability;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PartyAvailabilityRepository extends JpaRepository<PartyAvailability, Long> {
    List<PartyAvailability> findByCaseIdOrderByStartTimeAsc(Long caseId);
    List<PartyAvailability> findByCaseIdAndPartyOrderByStartTimeAsc(Long caseId, String party);
}
