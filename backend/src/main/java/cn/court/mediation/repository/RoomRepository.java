package cn.court.mediation.repository;

import cn.court.mediation.entity.MediationRoom;
import cn.court.mediation.enums.Channel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RoomRepository extends JpaRepository<MediationRoom, Long> {
    List<MediationRoom> findByEnabledTrueOrderByChannelAscIdAsc();

    List<MediationRoom> findByChannelAndEnabledTrueOrderByIdAsc(Channel channel);
}
