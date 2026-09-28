package com.vium.global.code;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "storage_methods")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StorageMethod {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Short id;

	private String code;

	private String name;
}
