package com.ames.mes_api.processpath;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * Maps {@code process_path} — approved process path master (AS-1).
 */
@Entity
@Table(name = "process_path")
public class ProcessPathEntity {

	@Id
	@Column(name = "process_path_id", length = 64, nullable = false)
	private String processPathId;

	@Column(name = "content", nullable = false, columnDefinition = "json")
	private String content;

	@Column(name = "description", length = 512)
	private String description;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private LocalDateTime createdAt;

	public String getProcessPathId() {
		return processPathId;
	}

	public void setProcessPathId(String processPathId) {
		this.processPathId = processPathId;
	}

	public String getContent() {
		return content;
	}

	public void setContent(String content) {
		this.content = content;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}
}
