package com.vium.shopping.controller;

import com.vium.global.common.ApiResponse;
import com.vium.global.security.CurrentUserProvider;
import com.vium.shopping.dto.request.ShoppingListItemCreateRequest;
import com.vium.shopping.dto.request.ShoppingListItemUpdateRequest;
import com.vium.shopping.dto.response.ShoppingHelperResponse;
import com.vium.shopping.dto.response.ShoppingListItemResponse;
import com.vium.shopping.dto.response.ShoppingListResponse;
import com.vium.shopping.service.ShoppingHelperService;
import com.vium.shopping.service.ShoppingListService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class ShoppingController {

	private final ShoppingListService shoppingListService;
	private final ShoppingHelperService shoppingHelperService;
	private final CurrentUserProvider currentUserProvider;
	@PostMapping("/shopping-list-items")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<ShoppingListItemResponse> addShoppingListItem(
		@Valid @RequestBody ShoppingListItemCreateRequest request) {
		Long userId = currentUserProvider.getCurrentUserId();
		ShoppingListItemResponse response = shoppingListService.addItem(userId, request);
		return ApiResponse.ok(response);
	}

	@GetMapping("/shopping-list-items")
	public ApiResponse<ShoppingListResponse> getShoppingList() {
		Long userId = currentUserProvider.getCurrentUserId();
		ShoppingListResponse response = shoppingListService.getItems(userId);
		return ApiResponse.ok(response);
	}

	@PatchMapping("/shopping-list-items/{itemId}")
	public ApiResponse<ShoppingListItemResponse> updateCheckStatus(
		@PathVariable Long itemId,
		@Valid @RequestBody ShoppingListItemUpdateRequest request) {
		Long userId = currentUserProvider.getCurrentUserId();
		ShoppingListItemResponse response = shoppingListService.updateCheckStatus(
			userId, itemId, request);
		return ApiResponse.ok(response);
	}

	@DeleteMapping("/shopping-list-items/{itemId}")
	public ApiResponse<Void> deleteItem(@PathVariable Long itemId) {
		Long userId = currentUserProvider.getCurrentUserId();
		shoppingListService.deleteItem(userId, itemId);
		return ApiResponse.ok(null);
	}

	@GetMapping("/shopping-helper")
	public ApiResponse<ShoppingHelperResponse> getShoppingHelper() {
		Long userId = currentUserProvider.getCurrentUserId();
		ShoppingHelperResponse response = shoppingHelperService.getShoppingHelper(userId);
		return ApiResponse.ok(response);
	}
}
