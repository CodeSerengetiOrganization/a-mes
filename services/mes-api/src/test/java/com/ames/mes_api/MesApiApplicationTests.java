package com.ames.mes_api;

import com.ames.mes_api.panelregistration.PanelRegistrationRepository;
import com.ames.mes_api.panelregistration.WorkOrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Scaffold smoke test — no live MySQL required.
 * JPA excluded → mock repositories so Panel Registration beans can wire.
 */
@SpringBootTest(properties = {
		"spring.autoconfigure.exclude="
				+ "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
				+ "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
				+ "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
})
class MesApiApplicationTests {

	@MockitoBean
	private PanelRegistrationRepository panelRegistrationRepository;

	@MockitoBean
	private WorkOrderRepository workOrderRepository;

	@Test
	void contextLoads() {
	}

}
