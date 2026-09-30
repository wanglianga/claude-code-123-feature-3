package cn.court.mediation.repository;

import cn.court.mediation.entity.MeetingResource;
import cn.court.mediation.enums.ResourceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MeetingResourceRepository extends JpaRepository<MeetingResource, Long> {
    List<MeetingResource> findByEnabledTrueOrderByTypeAscIdAsc();
    List<MeetingResource> findByTypeAndEnabledTrueOrderByIdAsc(ResourceType type);
}
