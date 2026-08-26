package com.ames.mes_api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ames.mes_api.panelregistration.PanelRegistrationRepository;
import com.ames.mes_api.panelregistration.WoPpBindingRepository;
import com.ames.mes_api.processpath.ProcessPathRepository;
import com.ames.mes_api.stationgate.OperationEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * K3s probes depend on Actuator health. Repositories mocked — no live MySQL.
 */
@SpringBootTest(properties = {
		"spring.autoconfigure.exclude="
				+ "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
				+ "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
				+ "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
})
@AutoConfigureMockMvc
class ActuatorHealthTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PanelRegistrationRepository panelRegistrationRepository;

	@MockitoBean
	private WoPpBindingRepository woPpBindingRepository;

	@MockitoBean
	private ProcessPathRepository processPathRepository;

	@MockitoBean
	private OperationEventRepository operationEventRepository;

	@Test
	void liveness_returns200() throws Exception {
		mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
	}

	@Test
	void readiness_returns200() throws Exception {
		mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
	}
}

