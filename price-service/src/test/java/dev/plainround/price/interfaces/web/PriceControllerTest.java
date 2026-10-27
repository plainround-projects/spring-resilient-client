package dev.plainround.price.interfaces.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.plainround.price.application.CatalogService;
import dev.plainround.price.application.PriceService;
import dev.plainround.price.domain.CatalogEntry;
import dev.plainround.price.domain.NoPriceAvailable;
import dev.plainround.price.domain.PriceResult;
import dev.plainround.price.domain.ProviderBusy;
import dev.plainround.price.domain.ProviderTimeout;
import dev.plainround.price.domain.ProviderUnavailable;
import dev.plainround.price.domain.SkuNotFound;
import io.micrometer.tracing.Tracer;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The web layer only: the controllers, the advice and the MVC machinery; the services are test
 * doubles.
 */
@WebMvcTest({PriceController.class, CatalogController.class})
class PriceControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private PriceService prices;
    @MockitoBean private CatalogService catalog;
    @MockitoBean private Tracer tracer;

    @Test
    void aFreshPriceIs200WithStaleFalse() throws Exception {
        when(prices.price("sku-1")).thenReturn(new PriceResult(new BigDecimal("42.00"), false, 0));

        mockMvc.perform(get("/price/sku-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(42.00))
                .andExpect(jsonPath("$.stale").value(false));
    }

    @Test
    void aStalePriceIs200WithTheLabelAndTheAge() throws Exception {
        when(prices.price("sku-1")).thenReturn(new PriceResult(new BigDecimal("42.00"), true, 37));

        mockMvc.perform(get("/price/sku-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stale").value(true))
                .andExpect(jsonPath("$.ageSeconds").value(37));
    }

    @Test
    void aNeverPricedSkuWithAFailingProviderIs503WithRetryAfter() throws Exception {
        when(prices.price("sku-new"))
                .thenThrow(new NoPriceAvailable("sku-new", new ProviderUnavailable("down")));

        mockMvc.perform(get("/price/sku-new"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Provider unavailable"))
                .andExpect(jsonPath("$.detail").value("no price available for sku-new"));
    }

    @Test
    void aRefusedCallIsAlsoA503() throws Exception {
        when(prices.price("sku-new"))
                .thenThrow(
                        new NoPriceAvailable(
                                "sku-new", new ProviderBusy(new RuntimeException("full"))));

        mockMvc.perform(get("/price/sku-new")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void aNeverPricedSkuWhoseCallTimedOutIs504() throws Exception {
        when(prices.price("sku-new"))
                .thenThrow(
                        new NoPriceAvailable(
                                "sku-new", new ProviderTimeout(new RuntimeException("slow"))));

        mockMvc.perform(get("/price/sku-new"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Provider timed out"));
    }

    @Test
    void aSkuWithOddCharactersIs400NamingTheParameter() throws Exception {
        mockMvc.perform(get("/price/bad sku!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("sku"));
    }

    @Test
    void theCatalogListsProductsAndNamesAMissingOne() throws Exception {
        when(catalog.list()).thenReturn(List.of(new CatalogEntry("sku-1", "Item 1")));
        when(catalog.get("nope")).thenThrow(new SkuNotFound("nope"));

        mockMvc.perform(get("/skus"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Item 1"));
        mockMvc.perform(get("/skus/nope")).andExpect(status().isNotFound());
    }

    @Test
    void anUnexpectedErrorIs500WithoutLeakingTheMessage() throws Exception {
        when(prices.price("boom")).thenThrow(new IllegalStateException("internal-host-7 exploded"));

        mockMvc.perform(get("/price/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        "internal-host-7"))));
    }
}
