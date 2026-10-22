package com.vium.receipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import com.vium.image.entity.ImagePurpose;
import com.vium.image.entity.UploadedImage;
import com.vium.image.repository.UploadedImageRepository;
import com.vium.image.service.ImageStorage;
import com.vium.receipt.dto.OcrLine;
import com.vium.receipt.dto.ScanRequest;
import com.vium.receipt.entity.PurchaseItem;
import com.vium.receipt.entity.PurchaseStatus;
import com.vium.receipt.entity.PurchaseType;
import com.vium.receipt.repository.PurchaseItemRepository;
import com.vium.receipt.repository.PurchaseRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:purchase-scan;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Sql(scripts = "/db/auth-users.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class PurchaseScanIntegrationTest {
	private static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");
	@Autowired MockMvc mvc;
	@Autowired JsonMapper mapper;
	@Autowired JdbcTemplate jdbc;
	@Autowired TokenService tokens;
	@Autowired PurchaseRepository purchases;
	@Autowired UploadedImageRepository images;
	@MockitoSpyBean PurchaseItemRepository items;
	@MockitoBean Clock clock;
	@MockitoBean ImageStorage storage;
	private String bearer;

	@BeforeEach
	void setUp() {
		reset(items, storage);
		when(clock.instant()).thenReturn(NOW);
		when(clock.getZone()).thenReturn(ZoneOffset.UTC);
		for (String table : List.of("consumption_events", "inventory_items", "purchase_items", "purchases",
			"uploaded_images", "expiry_estimation_rules", "ingredient_catalog", "purchase_sources", "storage_methods", "units", "users")) {
			jdbc.update("delete from " + table);
		}
		jdbc.update("""
			insert into users (id,email,display_name,created_at,updated_at) values
			(42,'scan@example.com','Scan',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
			(43,'other@example.com','Other',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
			""");
		for (short id = 1; id <= 10; id++) jdbc.update("insert into units (id,code,name) values (?,?,?)", id, "u" + id, "단위" + id);
		jdbc.update("insert into storage_methods (id,code,name) values (1,'cold','냉장'),(2,'frozen','냉동'),(3,'room','실온')");
		jdbc.update("""
			insert into purchase_sources (id,code,name) values (1,'emart','이마트'),(2,'coupang','쿠팡'),
			(3,'market','전통시장'),(4,'convenience','편의점'),(5,'online','온라인쇼핑'),(6,'etc','기타')
			""");
		catalog(1, "우유");
		catalog(2, "두부");
		rule(1, 1, 1, 7);
		rule(2, 1, 3, 3);
		rule(3, 1, 2, 30);
		bearer = "Bearer " + tokens.issue(42L).accessToken();
	}

	@AfterEach
	void noExternalStorageCalls() {
		verifyNoInteractions(storage);
	}

	@ParameterizedTest
	@CsvSource({"receipts,receipt-emart.json,RECEIPT,4,1,2026-10-20T18:32:00+09:00,2026-10-27",
		"online-orders,online-coupang.json,ONLINE_ORDER,2,2,2026-10-19T00:00:00+09:00,2026-10-26"})
	void scanCreatesDraftWithMatchingAndExpiryButNoInventory(String path, String fixture, String type,
			int count, int sourceId, String purchasedAt, String expiresOn) throws Exception {
		String body;
		try (var input = getClass().getResourceAsStream("/ocr/" + fixture)) {
			body = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		}
		scan(path, body).andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.error").value(nullValue()))
			.andExpect(jsonPath("$.data.type").value(type)).andExpect(jsonPath("$.data.status").value("DRAFT"))
			.andExpect(jsonPath("$.data.imageId").value(nullValue()))
			.andExpect(jsonPath("$.data.purchaseSourceId").value(sourceId))
			.andExpect(jsonPath("$.data.purchasedAt").value(purchasedAt))
			.andExpect(jsonPath("$.data.createdAt").value("2026-10-22T09:00:00+09:00"))
			.andExpect(jsonPath("$.data.items.length()").value(count))
			.andExpect(jsonPath("$.data.items[0].ingredientCatalogId").value(1))
			.andExpect(jsonPath("$.data.items[0].ingredientName").value("우유"))
			.andExpect(jsonPath("$.data.items[0].storageMethodId").value(1))
			.andExpect(jsonPath("$.data.items[0].expiresOn").value(expiresOn));
		assertThat(purchases.findAll()).singleElement().satisfies(purchase -> {
			assertThat(purchase.getType()).isEqualTo(PurchaseType.valueOf(type));
			assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.DRAFT);
			assertThat(purchase.getUserId()).isEqualTo(42L);
		});
		assertThat(items.count()).isEqualTo(count);
		assertThat(jdbc.queryForObject("select count(*) from inventory_items", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from consumption_events", Integer.class)).isZero();
	}

	@Test
	void acceptsOnlyOwnedReadyReceiptImageAndPreservesIt() throws Exception {
		var image = image(42, ImagePurpose.RECEIPT, true);
		var request = request("우유 1000");
		var body = new ScanRequest(image.getId(), request.imageWidth(), request.imageHeight(), request.lines());
		scan("receipts", mapper.writeValueAsString(body)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.imageId").value(image.getId()));
		assertThat(purchases.findAll().getFirst().getImageId()).isEqualTo(image.getId());
		assertThat(images.existsById(image.getId())).isTrue();
	}

	@ParameterizedTest
	@CsvSource({"43,RECEIPT,true", "42,PROFILE,true", "42,RECIPE,true", "42,RECEIPT,false"})
	void hidesWrongOwnerPurposeAndUnreadyImages(long owner, ImagePurpose purpose, boolean ready) throws Exception {
		var image = image(owner, purpose, ready);
		var request = request("우유 1000");
		scan("receipts", mapper.writeValueAsString(new ScanRequest(image.getId(), 1080, 2400, request.lines())))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("IMAGE_NOT_FOUND"));
		assertThat(purchases.count()).isZero();
		assertThat(items.count()).isZero();
	}

	@Test
	void hidesMissingImage() throws Exception {
		scan("receipts", mapper.writeValueAsString(new ScanRequest(Long.MAX_VALUE, 1080, 2400, request("우유 1000").lines())))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("IMAGE_NOT_FOUND"));
		assertThat(purchases.count()).isZero();
	}

	@ParameterizedTest
	@MethodSource("invalidBodies")
	void rejectsInvalidRequestsWithoutSaving(String body) throws Exception {
		scan("receipts", body).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
		assertThat(purchases.count()).isZero();
		assertThat(items.count()).isZero();
	}

	static Stream<Arguments> invalidBodies() {
		var json = JsonMapper.builder().build();
		var valid = json.createObjectNode();
		valid.put("imageWidth", 1080).put("imageHeight", 2400);
		valid.putArray("lines").addObject().put("text", "우유 1000").put("left", 0).put("top", 0).put("right", 100).put("bottom", 20);
		List<Arguments> cases = new ArrayList<>();
		for (String key : List.of("imageWidth", "imageHeight", "lines")) {
			var body = valid.deepCopy(); body.remove(key); cases.add(Arguments.of(body.toString()));
			body = valid.deepCopy(); body.putNull(key); cases.add(Arguments.of(body.toString()));
		}
		for (String key : List.of("imageWidth", "imageHeight")) {
			for (int number : List.of(0, 10001)) {
				var body = valid.deepCopy(); body.put(key, number); cases.add(Arguments.of(body.toString()));
			}
		}
		for (int count : List.of(0, 301)) {
			var body = valid.deepCopy(); var lines = body.putArray("lines");
			for (int i = 0; i < count; i++) lines.add(valid.get("lines").get(0).deepCopy());
			cases.add(Arguments.of(body.toString()));
		}
		var nullLine = valid.deepCopy(); nullLine.putArray("lines").addNull(); cases.add(Arguments.of(nullLine.toString()));
		for (String key : List.of("text", "left", "top", "right", "bottom")) {
			var body = valid.deepCopy(); ((ObjectNode) body.get("lines").get(0)).remove(key); cases.add(Arguments.of(body.toString()));
			body = valid.deepCopy(); ((ObjectNode) body.get("lines").get(0)).putNull(key); cases.add(Arguments.of(body.toString()));
		}
		for (String key : List.of("left", "top", "right", "bottom")) {
			var body = valid.deepCopy(); ((ObjectNode) body.get("lines").get(0)).put(key, -1); cases.add(Arguments.of(body.toString()));
		}
		for (String key : List.of("left", "top")) {
			var body = valid.deepCopy(); ((ObjectNode) body.get("lines").get(0)).put(key, 999); cases.add(Arguments.of(body.toString()));
		}
		for (String text : List.of("", "가".repeat(201))) {
			var body = valid.deepCopy(); ((ObjectNode) body.get("lines").get(0)).put("text", text); cases.add(Arguments.of(body.toString()));
		}
		for (long id : List.of(0L, -1L)) {
			var body = valid.deepCopy(); body.put("imageId", id); cases.add(Arguments.of(body.toString()));
		}
		return cases.stream();
	}

	@ParameterizedTest
	@ValueSource(strings = {"receipts", "online-orders"})
	void unrecognizableTextDoesNotCreateDraft(String path) throws Exception {
		scan(path, mapper.writeValueAsString(request("안녕하세요", "   ")))
			.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("SCAN_UNRECOGNIZABLE"));
		assertThat(purchases.count()).isZero();
		assertThat(items.count()).isZero();
	}

	@Test
	void matchesLongestNameAndAvoidsSingleCharacterSubstrings() throws Exception {
		catalog(3, "파"); catalog(4, "무"); catalog(5, "서울우유"); catalog(6, "AB"); catalog(7, "BC");
		scan("receipts", mapper.writeValueAsString(request("서울우유 1L 1000", "파스타 2000", "무가당 3000",
			"국산 파 1000", "abc 1000"))).andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.items[0].ingredientCatalogId").value(5))
			.andExpect(jsonPath("$.data.items[1].ingredientCatalogId").value(nullValue()))
			.andExpect(jsonPath("$.data.items[1].ingredientName").value(nullValue()))
			.andExpect(jsonPath("$.data.items[1].storageMethodId").value(1))
			.andExpect(jsonPath("$.data.items[1].expiresOn").value(nullValue()))
			.andExpect(jsonPath("$.data.items[2].ingredientCatalogId").value(nullValue()))
			.andExpect(jsonPath("$.data.items[3].ingredientCatalogId").value(3))
			.andExpect(jsonPath("$.data.items[4].ingredientCatalogId").value(6));
	}

	@ParameterizedTest
	@CsvSource({"3,3,2026-10-25", "2,2,2026-11-21", "0,1,null"})
	void fallsBackThroughStorageMethods(short available, int expected, String expires) throws Exception {
		jdbc.update("delete from expiry_estimation_rules");
		if (available != 0) rule(1, 1, available, available == 3 ? 3 : 30);
		if (available == 3) rule(2, 1, 2, 30);
		var result = scan("receipts", mapper.writeValueAsString(request("우유 1000"))).andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.items[0].storageMethodId").value(expected));
		if (expires.equals("null")) result.andExpect(jsonPath("$.data.items[0].expiresOn").value(nullValue()));
		else result.andExpect(jsonPath("$.data.items[0].expiresOn").value(expires));
	}

	@Test
	void missingOrFutureDateUsesSeoulScanTimeAndMissingTotalUsesItemSum() throws Exception {
		scan("receipts", mapper.writeValueAsString(request("2099-01-01", "우유 1000", "두부 2000", "  ")))
			.andExpect(status().isCreated()).andExpect(jsonPath("$.data.purchasedAt").value("2026-10-22T09:00:00+09:00"))
			.andExpect(jsonPath("$.data.totalAmount").value(3000));
		assertThat(purchases.findAll().getFirst().getPurchasedAt()).isEqualTo(LocalDateTime.of(2026, 10, 22, 9, 0));
	}

	@Test
	void persistsNormalizedOverflowQuantityAndAmount() throws Exception {
		scan("receipts", mapper.writeValueAsString(request("두부 999999999g*2 100,000,001")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.items[0].quantity").value(1))
			.andExpect(jsonPath("$.data.items[0].unitId").value(1))
			.andExpect(jsonPath("$.data.items[0].amount").value(nullValue()))
			.andExpect(jsonPath("$.data.totalAmount").value(nullValue()));
		assertThat(items.findAll()).singleElement().satisfies(item -> {
			assertThat(item.getQuantity()).isEqualByComparingTo("1.000");
			assertThat(item.getAmount()).isNull();
		});
	}

	@Test
	void acceptsMaximumLineCountAndKeepsOnlyFirstHundredValidItems() throws Exception {
		var texts = new ArrayList<String>();
		for (int i = 0; i < 101; i++) texts.add("두부 1000");
		while (texts.size() < 300) texts.add(" ");
		var request = request(texts.toArray(String[]::new));
		scan("receipts", mapper.writeValueAsString(new ScanRequest(null, 10000, 10000, request.lines())))
			.andExpect(status().isCreated()).andExpect(jsonPath("$.data.items.length()").value(100))
			.andExpect(jsonPath("$.data.totalAmount").value(100000));
		assertThat(items.count()).isEqualTo(100);
	}

	@Test
	void joinedOnlineNameIsTruncatedBeforePersistence() throws Exception {
		String first = "두부" + "가".repeat(198);
		scan("online-orders", mapper.writeValueAsString(request(first, "나".repeat(200), "2000원")))
			.andExpect(status().isCreated());
		assertThat(items.findAll()).singleElement().satisfies(item -> assertThat(item.getRawName()).isEqualTo(first));
	}

	@Test
	void eachScanCreatesIndependentDraft() throws Exception {
		String body = mapper.writeValueAsString(request("우유 1000"));
		scan("receipts", body).andExpect(status().isCreated());
		scan("receipts", body).andExpect(status().isCreated());
		assertThat(purchases.count()).isEqualTo(2);
		assertThat(items.count()).isEqualTo(2);
	}

	@ParameterizedTest
	@ValueSource(strings = {"receipts", "online-orders"})
	void requiresAuthentication(String path) throws Exception {
		mvc.perform(post("/api/me/" + path + "/scan").contentType(MediaType.APPLICATION_JSON)
			.content(mapper.writeValueAsString(request("우유 1000")))).andExpect(status().isUnauthorized());
		assertThat(purchases.count()).isZero();
	}

	@Test
	void rollsBackPurchaseAndFirstItemWhenSecondItemSaveFails() throws Exception {
		doThrow(new IllegalStateException("test persistence failure"))
			.when(items).save(argThat(item -> item.getRawName().equals("두부")));
		scan("receipts", mapper.writeValueAsString(request("우유 1000", "두부 2000")))
			.andExpect(status().isInternalServerError());
		verify(items).save(argThat(item -> item.getRawName().equals("우유")));
		assertThat(purchases.count()).isZero();
		assertThat(items.count()).isZero();
	}

	private ResultActions scan(String path, String body) throws Exception {
		return mvc.perform(post("/api/me/" + path + "/scan").param("userId", "43")
			.header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ScanRequest request(String... texts) {
		List<OcrLine> lines = new ArrayList<>();
		for (int i = 0; i < texts.length; i++) lines.add(new OcrLine(texts[i], 0, i * 40, 1000, i * 40 + 20));
		return new ScanRequest(null, 1080, 2400, lines);
	}

	private UploadedImage image(long owner, ImagePurpose purpose, boolean ready) {
		var image = UploadedImage.pending(owner, purpose, "private", UUID.randomUUID().toString(), "image/png", 100, NOW);
		if (ready) image.markReady();
		return images.saveAndFlush(image);
	}

	private void catalog(long id, String name) {
		jdbc.update("insert into ingredient_catalog (id,default_unit_id,name,created_at) values (?,1,?,CURRENT_TIMESTAMP)", id, name);
	}

	private void rule(long id, long catalogId, int storageId, int days) {
		jdbc.update("insert into expiry_estimation_rules (id,ingredient_catalog_id,storage_method_id,shelf_life_days,effective_from) values (?,?,?,?,DATE '2026-01-01')",
			id, catalogId, storageId, days);
	}
}
