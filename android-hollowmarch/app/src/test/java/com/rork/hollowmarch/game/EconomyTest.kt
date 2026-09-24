package com.rork.hollowmarch.game

import com.rork.hollowmarch.world.Site
import com.rork.hollowmarch.world.World
import com.rork.hollowmarch.world.WorldGenerator
import com.rork.hollowmarch.world.isSettlement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The economy layer: what the land could yield, what each place sows and
 * reaps, what it eats, which materials it can reach and from where, and who
 * trades with whom. All of it the seed's own — same seed, same years, same
 * harvests forever — and its residue rides the save whole.
 */
class EconomyTest {

    private val world: World = WorldGenerator.generate(20260925L)
    private val sites = world.sites.filter { it.isSettlement && !it.ruined }
    private val start = world.currentYear + 1

    private val reader = EconomySimulation.fresh(world)

    private fun freshRun(): Pair<SettlementLedger, HistoricalSimulation> =
        Pair(SettlementLedger.fresh(world), HistoricalSimulation.fresh(world))

    private fun stepOne(ledger: SettlementLedger, sim: HistoricalSimulation, year: Int): List<AgeEvent> {
        val turns = ledger.stepYears(year, world.sites)
        return sim.stepYears(year, world.sites, ledger, turns)
    }

    /** Steps [n] years of economy only, standing [folk] everywhere, and returns the events. */
    private fun runYears(
        economy: EconomySimulation,
        n: Int,
        folk: Int,
        ledger: SettlementLedger = SettlementLedger.fresh(world)
    ): List<AgeEvent> = runYearsHunger(economy, n, folk, ledger).first

    /** As [runYears], but also returns the hunger seen across all the years. */
    private fun runYearsHunger(
        economy: EconomySimulation,
        n: Int,
        folk: Int,
        ledger: SettlementLedger = SettlementLedger.fresh(world)
    ): Pair<List<AgeEvent>, Map<Int, Int>> {
        val events = mutableListOf<AgeEvent>()
        val hunger = mutableMapOf<Int, Int>()
        val override = sites.associate { it.id to folk }
        (start until start + n).forEach { year ->
            economy.stepYear(year, world.sites, ledger, events, override).forEach { (id, count) ->
                hunger[id] = maxOf(hunger[id] ?: 0, count)
            }
        }
        return Pair(events, hunger)
    }

    /** What a place could gather per soul in a year, from the land alone. */
    private fun foodPerSoul(site: Site): Float {
        val pot = reader.potentialOf(site)
        val bestSuit = CropKind.entries.maxOf { it.suitability(reader.profileOf(site)) }
        return pot.grain * bestSuit * EconomySimulation.CROP_RATE +
            pot.grazing * EconomySimulation.MEAT_RATE +
            pot.game * EconomySimulation.GAME_RATE +
            pot.fish * EconomySimulation.FISH_RATE +
            pot.gathered * EconomySimulation.GATHER_RATE
    }

    private fun bestLand(): Site = sites.maxBy { foodPerSoul(it) }
    private fun worstLand(): Site = sites.minBy { foodPerSoul(it) }
    private fun woodiestLand(): Site = sites.maxBy { reader.potentialOf(it).timber }

    private fun siteWithDeposits(): Site? =
        sites.firstOrNull { reader.depositsOf(it).isNotEmpty() }

    /** The two living settlements nearest each other, for a road. */
    private fun nearestPair(): Pair<Site, Site> {
        val ordered = sites.sortedBy { it.id }
        var best = Pair(ordered[0], ordered[1])
        var bestD = Float.MAX_VALUE
        for (a in ordered) for (b in ordered) {
            if (a.id >= b.id) continue
            val d = (a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y)
            if (d < bestD) {
                bestD = d
                best = Pair(a, b)
            }
        }
        return best
    }

    // ------------------------------------------------------------------ terrain

    @Test
    fun terrainReadsArePureAndSeeded() {
        val other = EconomySimulation.fresh(world)
        for (site in sites) {
            assertEquals(reader.profileOf(site), other.profileOf(site))
            assertEquals(reader.potentialOf(site), other.potentialOf(site))
            assertEquals(Ecology.potentialOf(Ecology.profileOf(world, site)), reader.potentialOf(site))
        }
        assertNotEquals(
            "the province is not one flat field",
            reader.profileOf(sites.first()),
            reader.profileOf(sites.last())
        )
    }

    @Test
    fun valleyLandFeedsAndFishes() {
        val valley = LandProfile(
            elevation = 0.20f, roughness = 0.15f, moisture = 0.55f, forest = 0.30f,
            water = 0.42f, soil = 0.70f, warmth = 0.60f, saltBand = 0f
        )
        assertEquals(LandCharacter.VALLEY, Ecology.characterOf(valley))
        val pot = Ecology.potentialOf(valley)
        assertTrue("a valley grows grain", pot.grain >= 0.45f)
        assertTrue("a valley fishes", pot.fish >= 0.30f)
        assertTrue("a valley digs clay", pot.clay >= 0.25f)
    }

    @Test
    fun mountainLandQuarriesAndStarves() {
        val mountain = LandProfile(
            elevation = 0.80f, roughness = 0.70f, moisture = 0.30f, forest = 0.10f,
            water = 0.05f, soil = 0.15f, warmth = 0.40f, saltBand = 0f
        )
        assertEquals(LandCharacter.MOUNTAIN, Ecology.characterOf(mountain))
        val pot = Ecology.potentialOf(mountain)
        assertTrue("a mountain quarries", pot.stone >= 0.60f)
        assertTrue("a mountain grows little grain", pot.grain <= 0.20f)
        assertTrue("a mountain holds little timber", pot.timber <= 0.30f)
    }

    @Test
    fun forestLandGivesTimberAndGame() {
        val forest = LandProfile(
            elevation = 0.35f, roughness = 0.30f, moisture = 0.60f, forest = 0.75f,
            water = 0.15f, soil = 0.35f, warmth = 0.50f, saltBand = 0f
        )
        assertEquals(LandCharacter.FOREST, Ecology.characterOf(forest))
        val pot = Ecology.potentialOf(forest)
        assertTrue("a forest gives timber", pot.timber >= 0.60f)
        assertTrue("a forest gives game", pot.game >= 0.50f)
    }

    // -------------------------------------------------------------------- crops

    @Test
    fun cropSuitabilityIsDeterministicAndDifferentiated() {
        val coldThin = LandProfile(
            elevation = 0.30f, roughness = 0.20f, moisture = 0.40f, forest = 0.20f,
            water = 0.10f, soil = 0.25f, warmth = 0.20f, saltBand = 0f
        )
        val hotRich = LandProfile(
            elevation = 0.15f, roughness = 0.10f, moisture = 0.50f, forest = 0.10f,
            water = 0.20f, soil = 0.60f, warmth = 0.85f, saltBand = 0f
        )
        CropKind.entries.forEach { crop ->
            assertEquals(crop.suitability(coldThin), crop.suitability(coldThin))
            assertEquals(crop.suitability(hotRich), crop.suitability(hotRich))
        }
        assertTrue("hardy barley beats grain in the cold thin soil",
            CropKind.BARLEY.suitability(coldThin) > CropKind.GRAIN.suitability(coldThin))
        assertTrue("roots beat grain in the cold thin soil",
            CropKind.ROOTS.suitability(coldThin) > CropKind.GRAIN.suitability(coldThin))
        assertTrue("orchard fruit beats grain where the summers burn",
            CropKind.FRUIT.suitability(hotRich) > CropKind.GRAIN.suitability(hotRich))
    }

    @Test
    fun onlySuitableCropsTakeRoot() {
        val economy = EconomySimulation.fresh(world)
        val site = worstLand()
        runYears(economy, 60, 200)
        val prof = economy.profileOf(site)
        economy.cropsOf(site).forEach { (crop, _) ->
            assertTrue(
                "${crop.label} took root on land that cannot feed it",
                crop.suitability(prof) >= EconomySimulation.ADOPT_FLOOR
            )
        }
    }

    @Test
    fun cropsArriveGetEstablishedAndFeed() {
        val economy = EconomySimulation.fresh(world)
        val site = bestLand()
        val ledger = SettlementLedger.fresh(world)
        runYears(economy, 20, 120, ledger)
        val stages = economy.cropsOf(site)
        assertTrue("something was sown at ${site.name}", stages.isNotEmpty())
        assertTrue(
            "a sown field grows sure with the years",
            stages.values.any { it == CropStage.ESTABLISHED }
        )
        assertTrue("a fed place keeps a granary", economy.reserveOf(site) > 0)
    }

    @Test
    fun yieldAnswersToFolk() {
        val site = bestLand()
        val few = EconomySimulation.fresh(world)
        val many = EconomySimulation.fresh(world)
        runYears(few, 30, 60)
        runYears(many, 30, 240)
        assertTrue(
            "more hands, more in the granary",
            many.reserveOf(site) > few.reserveOf(site)
        )
    }

    // --------------------------------------------------------------------- food

    @Test
    fun reservesPersistThroughTheSave() {
        val economy = EconomySimulation.fresh(world)
        val site = bestLand()
        runYears(economy, 30, 120)
        val restored = EconomySimulation.fresh(world)
        restored.applyEncoded(economy.encode())
        assertEquals(economy.reserveOf(site), restored.reserveOf(site))
        assertEquals(economy.cropsOf(site), restored.cropsOf(site))
        assertEquals(economy.routesOf(site), restored.routesOf(site))
        assertEquals(economy.timberOf(site), restored.timberOf(site))
    }

    @Test
    fun hungerSendsWalkers() {
        val economy = EconomySimulation.fresh(world)
        val site = worstLand()
        val (events, hunger) = runYearsHunger(economy, 20, 30000)
        assertTrue(
            "a place too great for its land goes hungry",
            (hunger[site.id] ?: 0) > 0
        )
        assertTrue(
            "the chronicle remembers a failed harvest",
            events.any { it.kind == AgeEventKind.HARVEST_FAILURE && it.siteId == site.id }
        )
    }

    @Test
    fun hungryWalkersTakeTheMigrationRoad() {
        val (ledger, sim) = freshRun()
        val origin = sites.maxBy { ledger.folkOf(it) }
        val before = world.sites.filter { it.isSettlement }.associate { it.id to ledger.folkOf(it) }
        val beforeOrigin = before.getValue(origin.id)
        val events = mutableListOf<AgeEvent>()
        sim.applySiteYear(origin, beforeOrigin, null, world.sites, ledger, start, events, hungry = 7)
        val migration = events.first { it.kind == AgeEventKind.MIGRATION }
        assertTrue(migration.text.contains("hungry souls"))
        assertTrue(migration.text.contains(origin.name))
        val received = world.sites.first { it.id == migration.otherSiteId }
        assertNotEquals(origin.id, received.id)
        assertTrue(migration.text.contains(received.name))
        assertEquals("the hungry leave the living count", beforeOrigin - 7, ledger.folkOf(origin))
        val arrived = world.sites
            .filter { it.isSettlement && it.id != origin.id }
            .sumOf { ledger.folkOf(it) - before.getValue(it.id) }
        assertEquals("no soul was lost on the road", 7, arrived)
    }

    @Test
    fun aFatYearFillsTheGranary() {
        val economy = EconomySimulation.fresh(world)
        val site = bestLand()
        val events = runYears(economy, 30, 80)
        val fat = events.filter { it.kind == AgeEventKind.HARVEST_SURPLUS }
        assertTrue(
            "a fat year on fat land enters the chronicle",
            fat.isNotEmpty() || economy.reserveOf(site) > 80 * EconomySimulation.RESERVE_RICH
        )
    }

    // ------------------------------------------------------- timber and digging

    @Test
    fun timberRegrowsUnderAFairHand() {
        val economy = EconomySimulation.fresh(world)
        val site = woodiestLand()
        val init = economy.timberOf(site)
        assertTrue("the province has woods", init >= 300)
        runYears(economy, 10, 60)
        assertTrue(
            "a fair hand takes no more than the forest gives back",
            economy.timberOf(site) >= init * 9 / 10
        )
    }

    @Test
    fun overloggingCutsTheWoodsOut() {
        val economy = EconomySimulation.fresh(world)
        val site = woodiestLand()
        val init = economy.timberOf(site)
        val events = runYears(economy, 12, 5000)
        assertTrue("the woods do not stand before ten thousand axes", economy.timberOf(site) < init / 5)
        assertTrue(
            "the chronicle remembers the cut-out woods",
            events.any {
                it.kind == AgeEventKind.RESOURCE_DEPLETED &&
                    it.siteId == site.id && it.text.contains("woods")
            }
        )
    }

    @Test
    fun depositsAreFoundWorkedAndWorkedOut() {
        val economy = EconomySimulation.fresh(world)
        val site = siteWithDeposits()
        assertTrue("the province holds metal", site != null)
        val theSite = site!!
        val richness = reader.depositsOf(theSite).associate { it.material to it.richness }
        runYears(economy, 40, 300)
        val found = economy.depositsOf(theSite)
        assertTrue("growing folk find what the ground holds", found.all { it.discovered })
        runYears(economy, 60, 20000)
        val workedOut = economy.depositsOf(theSite)
        assertTrue("no pit is bottomless", workedOut.all { it.remaining == 0 })
        assertTrue("richness was once there", richness.all { it.value >= 400 })
    }

    @Test
    fun depositsStayWithinTheRegionsMaterialLaw() {
        val economy = EconomySimulation.fresh(world)
        val geography = MaterialGeography(world)
        for (site in sites) {
            val allowed = geography.available(economy.cultureOf(site)).toSet()
            economy.depositsOf(site).forEach { dep ->
                assertTrue(
                    "${dep.material.label} was drawn where the region law says it cannot be",
                    dep.material in allowed
                )
            }
        }
    }

    // ---------------------------------------------------------------- materials

    @Test
    fun materialsComeFromTheLandTheRoadOrNowhere() {
        val economy = EconomySimulation.fresh(world)
        val ledger = SettlementLedger.fresh(world)
        runYears(economy, 40, 600, ledger)
        var sawLocal = false
        var sawScarce = false
        var sawImported = false
        for (site in sites) {
            val report = economy.materialReport(site, ledger, world.sites)
            assertEquals("every material gets an answer", Material.entries.size, report.size)
            report.forEach { provenance ->
                when (provenance.source) {
                    MaterialSource.LOCAL -> sawLocal = true
                    MaterialSource.SCARCE -> sawScarce = true
                    MaterialSource.IMPORTED -> {
                        sawImported = true
                        assertTrue("an import names its road", provenance.fromSiteId > 0)
                        assertTrue(provenance.fromSiteName.isNotBlank())
                        assertEquals(
                            "the road's other end truly has it",
                            MaterialSource.LOCAL,
                            economy.provenanceOf(
                                world.sites.first { it.id == provenance.fromSiteId },
                                ledger, world.sites, provenance.material
                            ).source
                        )
                    }
                }
            }
        }
        assertTrue("someplace works something with its own hands", sawLocal)
        assertTrue("someplace cannot reach something", sawScarce)
        if (sites.any { economy.routesOf(it).isNotEmpty() }) {
            assertTrue("roads bring what the ground does not", sawImported)
        }
    }

    // -------------------------------------------------------------------- trade

    @Test
    fun tradeRoadsFormWhereTheLandComplements() {
        val economy = EconomySimulation.fresh(world)
        val ledger = SettlementLedger.fresh(world)
        runYears(economy, 30, 400, ledger)
        val allRoutes = sites.flatMap { economy.routesOf(it) }.distinctBy { it.key }
        assertTrue("complementary ground finds a road", allRoutes.isNotEmpty())
        allRoutes.forEach { route ->
            val a = world.sites.first { it.id == route.fromId }
            val b = world.sites.first { it.id == route.toId }
            val dx = a.x - b.x
            val dy = a.y - b.y
            assertTrue("roads are short", dx * dx + dy * dy <= EconomySimulation.TRADE_MAX_DIST_SQ)
            if (route.resource != ResourceKind.ORE) {
                assertTrue(
                    "${route.resource.label} must be rich at one end and poor at the other",
                    reader.potentialOf(a)[route.resource] >= EconomySimulation.TRADE_RICH &&
                        reader.potentialOf(b)[route.resource] <= EconomySimulation.TRADE_POOR
                )
            }
        }
        // and the roads ride the save
        val withRoads = sites.first { economy.routesOf(it).isNotEmpty() }
        val restored = EconomySimulation.fresh(world)
        restored.applyEncoded(economy.encode())
        assertEquals(economy.routesOf(withRoads), restored.routesOf(withRoads))
    }

    @Test
    fun aRoadToARuinedPlaceFallsOutOfUse() {
        val economy = EconomySimulation.fresh(world)
        val livingIds = sites.map { it.id }.toSet()
        val dead = world.sites.first { it.id !in livingIds }
        val a = sites.first()
        // a crafted save: one road to a place that no longer answers
        val crafted = "S${a.id}=500=0=0=0=0=0===${a.id}>${dead.id}:GRAIN="
        economy.applyEncoded(crafted)
        assertTrue(economy.routesOf(a).isNotEmpty())
        val events = runYears(economy, 1, 100)
        assertTrue("the road is gone", economy.routesOf(a).isEmpty())
        assertTrue(
            "the chronicle remembers the road that fell",
            events.any {
                it.kind == AgeEventKind.TRADE_ROUTE_CLOSED &&
                    it.text.contains(a.name)
            }
        )
    }

    // ------------------------------------------------------------ specialization

    @Test
    fun specializationTakesHoldAndEntersTheChronicle() {
        val economy = EconomySimulation.fresh(world)
        val events = runYears(economy, 80, 300)
        assertTrue(
            "places come to be known for their work",
            events.any { it.kind == AgeEventKind.SPECIALIZED }
        )
        val site = bestLand()
        val labels = economy.specializationOf(site, 300)
        assertTrue(labels.isNotEmpty())
        assertEquals("an identity is derived, not rolled", labels, economy.specializationOf(site, 300))
    }

    // -------------------------------------------------------------- the long run

    @Test
    fun theSameSeedProducesTheSameEconomyTwice() {
        val a = EconomySimulation.fresh(world)
        val b = EconomySimulation.fresh(world)
        runYears(a, 40, 150)
        runYears(b, 40, 150)
        assertEquals(a.encode(), b.encode())
    }

    @Test
    fun anEconomySavedMidwayResumesAsIfItNeverStopped() {
        val end = start + 80
        val (ledgerA, simA) = freshRun()
        (start until end).forEach { year -> stepOne(ledgerA, simA, year) }

        val (ledgerB, simB) = freshRun()
        val mid = start + 40
        (start until mid).forEach { year -> stepOne(ledgerB, simB, year) }
        val ledgerReloaded = SettlementLedger.fromEncoded(ledgerB.encode(), world)
        val simReloaded = HistoricalSimulation.fromSave(world, simB.encode())
        (mid until end).forEach { year -> stepOne(ledgerReloaded, simReloaded, year) }

        assertEquals(ledgerA.encode(), ledgerReloaded.encode())
        assertEquals("the whole layer rides the save", simA.encode(), simReloaded.encode())
        assertEquals(simA.eventLog(), simReloaded.eventLog())
        // the economy's own arithmetic agrees, item by item
        val midRun = simA.economy
        val reloaded = simReloaded.economy
        sites.forEach { site ->
            assertEquals(midRun.cropsOf(site), reloaded.cropsOf(site))
            assertEquals(midRun.depositsOf(site), reloaded.depositsOf(site))
            assertEquals(midRun.routesOf(site), reloaded.routesOf(site))
            assertEquals(midRun.reserveOf(site), reloaded.reserveOf(site))
        }
    }

    @Test
    fun aCenturyOfYearsStaysBoundedAndReadable() {
        val (ledger, sim) = freshRun()
        val end = start + 120
        (start until end).forEach { year -> stepOne(ledger, sim, year) }
        assertTrue("the world remembers what matters", sim.eventLog().size <= HistoricalSimulation.EVENT_LOG_CAP)
        sim.eventLog().forEach { event ->
            assertTrue(event.text.isNotBlank())
            assertEquals(event.year, event.toChronicle().year)
        }
        val economyKinds = setOf(
            AgeEventKind.RESOURCE_DISCOVERED, AgeEventKind.RESOURCE_DEPLETED,
            AgeEventKind.HARVEST_FAILURE, AgeEventKind.HARVEST_SURPLUS,
            AgeEventKind.TRADE_ROUTE_OPENED, AgeEventKind.TRADE_ROUTE_CLOSED,
            AgeEventKind.SPECIALIZED
        )
        sim.eventLog().filter { it.kind in economyKinds }.forEach { event ->
            val chronicle = event.toChronicle()
            assertTrue("economy news lands in kinds the journal knows", chronicle.kind in com.rork.hollowmarch.world.EventKind.entries)
            assertFalse("economy news names its place", chronicle.text.contains("null"))
        }
    }
}
