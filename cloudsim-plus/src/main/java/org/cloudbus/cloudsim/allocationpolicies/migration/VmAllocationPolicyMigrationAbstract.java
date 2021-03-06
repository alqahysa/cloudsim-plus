/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */
package org.cloudbus.cloudsim.allocationpolicies.migration;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.cloudbus.cloudsim.allocationpolicies.VmAllocationPolicy;
import org.cloudbus.cloudsim.allocationpolicies.VmAllocationPolicyAbstract;
import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.lists.VmList;
import org.cloudbus.cloudsim.util.Log;
import org.cloudbus.cloudsim.selectionpolicies.power.PowerVmSelectionPolicy;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudbus.cloudsim.core.Simulation;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

/**
 * An abstract VM allocation policy that dynamically optimizes the
 * VM allocation (placement) using migration.
 * <b>It's a Best Fit policy which selects the Host with most efficient power usage to place a given VM.</b>
 * Such a behaviour can be overridden by sub-classes.
 *
 * <p>If you are using any algorithms, policies or workload included in the
 * power package please cite the following paper:
 *
 * <ul>
 * <li><a href="http://dx.doi.org/10.1002/cpe.1867">Anton Beloglazov, and
 * Rajkumar Buyya, "Optimal Online Deterministic Algorithms and Adaptive
 * Heuristics for Energy and Performance Efficient Dynamic Consolidation of
 * Virtual Machines in Cloud Data Centers", Concurrency and Computation:
 * Practice and Experience (CCPE), Volume 24, Issue 13, Pages: 1397-1420, John
 * Wiley & Sons, Ltd, New York, USA, 2012</a>
 * </ul>
 * </p>
 *
 * @author Anton Beloglazov
 * @author Manoel Campos da Silva Filho
 * @since CloudSim Toolkit 3.0
 */
public abstract class VmAllocationPolicyMigrationAbstract extends VmAllocationPolicyAbstract implements VmAllocationPolicyMigration {

    /**@see #getUnderUtilizationThreshold() */
    private double underUtilizationThreshold;

    /**
     * The vm selection policy.
     */
    private PowerVmSelectionPolicy vmSelectionPolicy;

    /**
     * A map between a VM and the host where it is placed.
     */
    private final Map<Vm, Host> savedAllocation;

    /**
     * A map of CPU utilization history (in percentage) for each host, where
     * each key is a hos and each value is the CPU utilization percentage history.
     *
     * @todo there is inconsistence between these data.
     * Into the Host, it is stored the actual utilization for the given time.
     * Here it is stored the utilization as it was computed
     * by the VmAllocationPolicy implementation.
     * For instance, the {@link VmAllocationPolicyMigrationLocalRegression}
     * used Local Regression to predict Host utilization
     * and such value will be stored in this map.
     * However, these duplicate and inconsistent data
     * are confusing and error prone.
     */
    private final Map<Host, List<Double>> utilizationHistory;

    /**
     * @see #getMetricHistory()
     */
    private final Map<Host, List<Double>> metricHistory;

    /**
     * @see #getTimeHistory()
     */
    private final Map<Host, List<Double>> timeHistory;

    /**
     * Creates a VmAllocationPolicyMigrationAbstract.
     *
     * @param vmSelectionPolicy the policy that defines how VMs are selected for migration
     */
    public VmAllocationPolicyMigrationAbstract(final PowerVmSelectionPolicy vmSelectionPolicy) {
        this(vmSelectionPolicy, null);
    }

    /**
     * Creates a new VmAllocationPolicy, changing the {@link Function} to select a Host for a Vm.
     * @param vmSelectionPolicy the policy that defines how VMs are selected for migration
     * @param findHostForVmFunction a {@link Function} to select a Host for a given Vm.
     *                              Passing null makes the Function to be set as the default {@link #findHostForVm(Vm)}.
     * @see VmAllocationPolicy#setFindHostForVmFunction(java.util.function.BiFunction)
     */
    public VmAllocationPolicyMigrationAbstract(
        final PowerVmSelectionPolicy vmSelectionPolicy,
        final BiFunction<VmAllocationPolicy, Vm, Optional<Host>> findHostForVmFunction)
    {
        super(findHostForVmFunction);
        this.underUtilizationThreshold = 0.35;
        this.savedAllocation = new HashMap<>();
        this.utilizationHistory = new HashMap<>();
        this.metricHistory = new HashMap<>();
        this.timeHistory = new HashMap<>();
        setVmSelectionPolicy(vmSelectionPolicy);
    }

    @Override
    public Map<Vm, Host> getOptimizedAllocationMap(final List<? extends Vm> vmList) {
        //@todo See https://github.com/manoelcampos/cloudsim-plus/issues/94
        final Set<Host> overloadedHosts = getOverloadedHosts();
        printOverUtilizedHosts(overloadedHosts);
        saveAllocation();

        final Map<Vm, Host> migrationMap = getMigrationMapFromOverloadedHosts(overloadedHosts);
        updateMigrationMapFromUnderloadedHosts(overloadedHosts, migrationMap);
        restoreAllocation();
        return migrationMap;
    }

    /**
     * Updates the  map of VMs that will be migrated from under utilized hosts.
     *
     * @param overloadedHosts the List of over utilized hosts
     * @param migrationMap current migration map that will be updated
     */
    private void updateMigrationMapFromUnderloadedHosts(
        final Set<Host> overloadedHosts,
        final Map<Vm, Host> migrationMap)
    {
        final List<Host> switchedOffHosts = getSwitchedOffHosts();

        // over-utilized hosts + hosts that are selected to migrate VMs to from over-utilized hosts
        final Set<Host> excludedHostsFromUnderloadSearch = new HashSet<>();
        excludedHostsFromUnderloadSearch.addAll(overloadedHosts);
        excludedHostsFromUnderloadSearch.addAll(switchedOffHosts);
        /*
        During the computation of the new placement for VMs
        the current VM placement is changed temporarily, before the actual migration of VMs.
        If VMs are being migrated from overloaded Hosts, they in fact already were removed
        from such Hosts and moved to destination ones.
        The target Host that maybe were shut down, might become underloaded too.
        This way, such Hosts are added to be ignored when
        looking for underloaded Hosts.
        See https://github.com/manoelcampos/cloudsim-plus/issues/94
         */
        excludedHostsFromUnderloadSearch.addAll(migrationMap.values());

        // over-utilized + under-utilized hosts
        final Set<Host> excludedHostsForFindingNewVmPlacement = new HashSet<>();
        excludedHostsForFindingNewVmPlacement.addAll(overloadedHosts);
        excludedHostsForFindingNewVmPlacement.addAll(switchedOffHosts);

        final int numberOfHosts = getHostList().size();

        while (true) {
            if (numberOfHosts == excludedHostsFromUnderloadSearch.size()) {
                break;
            }

            final Host underloadedHost = getUnderloadedHost(excludedHostsFromUnderloadSearch);
            if (underloadedHost == Host.NULL) {
                break;
            }

            Log.printFormattedLine("%.2f: PowerVmAllocationPolicy: Underloaded hosts: %s", getDatacenter().getSimulation().clock(),  underloadedHost);

            excludedHostsFromUnderloadSearch.add(underloadedHost);
            excludedHostsForFindingNewVmPlacement.add(underloadedHost);

            List<? extends Vm> vmsToMigrateFromUnderloadedHost = getVmsToMigrateFromUnderUtilizedHost(underloadedHost);
            if (!vmsToMigrateFromUnderloadedHost.isEmpty()) {
                Log.printFormatted("\tVMs to be reallocated from the underloaded Host %d: ", underloadedHost.getId());
                printVmIds(vmsToMigrateFromUnderloadedHost);

                final Map<Vm, Host> newVmPlacement = getNewVmPlacementFromUnderloadedHost(
                        vmsToMigrateFromUnderloadedHost,
                        excludedHostsForFindingNewVmPlacement);

                excludedHostsFromUnderloadSearch.addAll(extractHostListFromMigrationMap(newVmPlacement));
                migrationMap.putAll(newVmPlacement);
                Log.printLine();
            }
        }
    }

    private void printVmIds(final List<? extends Vm> vmList) {
        if (!Log.isDisabled()) {
            vmList.forEach(vm -> Log.printFormatted("Vm %d ", vm.getId()));
            Log.printLine();
        }
    }

    /**
     * Prints the over utilized hosts.
     *
     * @param overloadedHosts the over utilized hosts
     */
    private void printOverUtilizedHosts(final Set<Host> overloadedHosts) {
        if (!Log.isDisabled() && !overloadedHosts.isEmpty()) {
            Log.printFormattedLine("%.2f: PowerVmAllocationPolicy: Overloaded hosts in %s: %s",
                getDatacenter().getSimulation().clock(), getDatacenter(),
                overloadedHosts.stream().map(h -> String.valueOf(h.getId())).collect(joining(",")));
        }
    }

    /**
     * Gets the power consumption different after the supposed placement of a VM into a given Host
     * and the original Host power consumption.
     *
     * @param host the host to check the power consumption
     * @param vm the candidate vm
     * @return the host power consumption different after the supposed VM placement or 0 if the power
     * consumption could not be determined
     */
    protected double getPowerAfterAllocationDifference(final Host host, final Vm vm){
        final double powerAfterAllocation = getPowerAfterAllocation(host, vm);
        if (powerAfterAllocation > 0) {
            return powerAfterAllocation - host.getPowerModel().getPower();
        }

        return 0;
    }

    /**
     * Checks if a host will be over utilized after placing of a candidate VM.
     *
     * @param host the host to verify
     * @param vm the candidate vm
     * @return true, if the host will be over utilized after VM placement; false
     * otherwise
     */
    protected boolean isNotHostOverloadedAfterAllocation(final Host host, final Vm vm) {
        boolean isHostOverUsedAfterAllocation = true;
        if (host.createTemporaryVm(vm)) {
            isHostOverUsedAfterAllocation = isHostOverloaded(host);
            host.destroyTemporaryVm(vm);
        }
        return !isHostOverUsedAfterAllocation;
    }

    @Override
    public Optional<Host> findHostForVm(final Vm vm) {
        final Set<Host> excludedHosts = new HashSet<>();
        excludedHosts.add(vm.getHost());
        return findHostForVm(vm, excludedHosts);
    }

    /**
     * Finds a Host that has enough resources to place a given VM and that will not
     * be overloaded after the placement. The selected Host will be that
     * one with most efficient power usage for the given VM.
     *
     * <p>This method performs the basic filtering and delegates additional ones
     * and the final selection of the Host to other method.</p>
     *
     * @param vm the VM
     * @param excludedHosts the excluded hosts
     * @return an {@link Optional} containing a suitable Host to place the VM or an empty {@link Optional} if not found
     * @see #findHostForVmInternal(Vm, Stream)
     */
    public Optional<Host> findHostForVm(final Vm vm, final Set<? extends Host> excludedHosts) {
        /*The predicate always returns true to indicate that in fact it is not
        applying any additional filter.*/
        return findHostForVm(vm, excludedHosts, host -> true);
    }

    /**
     * Finds a Host that has enough resources to place a given VM and that will not
     * be overloaded after the placement. The selected Host will be that
     * one with most efficient power usage for the given VM.
     *
     * <p>This method performs the basic filtering and delegates additional ones
     * and the final selection of the Host to other method.</p>
     *
     * @param vm the VM
     * @param excludedHosts the excluded hosts
     * @param predicate an additional {@link Predicate} to be used to filter
     *                  the Host to place the VM
     * @return an {@link Optional} containing a suitable Host to place the VM or an empty {@link Optional} if not found
     * @see #findHostForVmInternal(Vm, Stream)
     */
    public Optional<Host> findHostForVm(final Vm vm, final Set<? extends Host> excludedHosts, final Predicate<Host> predicate) {
        final Stream<Host> stream = this.getHostList().stream()
            .filter(h -> !excludedHosts.contains(h))
            .filter(h -> h.isSuitableForVm(vm))
            .filter(h -> isNotHostOverloadedAfterAllocation(h, vm))
            .filter(predicate);

        return findHostForVmInternal(vm, stream);
    }

    /**
     * Applies additional filters to the Hosts Stream and performs the actual Host selection.
     * This method is a Stream's final operation, that it, it closes the Stream and returns an {@link Optional} value.
     *
     * <p>This method can be overridden by sub-classes to change the method used to select the Host for the given VM.</p>
     *
     * @param vm the VM to find a Host to be placed into
     * @param hostStream a {@link Stream} containing the Hosts after passing the basic filtering
     * @return an {@link Optional} containing a suitable Host to place the VM or an empty {@link Optional} if not found
     * @see #findHostForVm(Vm, Set)
     * @see #additionalHostFilters(Vm, Stream)
     */
    protected Optional<Host> findHostForVmInternal(final Vm vm, final Stream<Host> hostStream){
        final Comparator<Host> hostPowerConsumptionComparator =
            Comparator.comparingDouble(h -> getPowerAfterAllocationDifference(h, vm));

        return additionalHostFilters(vm, hostStream).min(hostPowerConsumptionComparator);
    }

    /**
     * Applies additional filters to select a Host to place a given VM.
     * This implementation filters the stream of Hosts to get those ones
     * that the placement of the VM impacts its power usage.
     *
     * <p>This method can be overridden by sub-classes to change filtering.</p>
     *
     * @param vm the VM to find a Host to be placed into
     * @param hostStream a {@link Stream} containing the Hosts after passing the basic filtering
     * @return the Hosts {@link Stream} after applying the additional filters
     */
    protected Stream<Host> additionalHostFilters(final Vm vm, final Stream<Host> hostStream){
        return hostStream.filter(h -> getPowerAfterAllocation(h, vm) > 0);
    }

    /**
     * Extracts the host list from a migration map.
     *
     * @param migrationMap the migration map
     * @return the list
     */
    protected List<Host> extractHostListFromMigrationMap(final Map<Vm, Host> migrationMap) {
        return migrationMap.entrySet().stream()
                .map(Map.Entry::getValue)
                .collect(toList());
    }

    /**
     * Gets a new VM placement considering the list of VM to migrate
     * from overloaded Hosts.
     *
     * @param overloadedHosts the list of overloaded Hosts
     * @return the new VM placement map where each key is a VM
     * and each value is the Host to place it.
     */
    protected Map<Vm, Host> getMigrationMapFromOverloadedHosts(final Set<Host> overloadedHosts) {
        final List<Vm> vmsToMigrate = getVmsToMigrateFromOverloadedHosts(overloadedHosts);
        final Map<Vm, Host> migrationMap = new HashMap<>();
        if(overloadedHosts.isEmpty()) {
            return migrationMap;
        }

        Log.printLine("\tReallocation of VMs from overloaded hosts: ");
        VmList.sortByCpuUtilization(vmsToMigrate, getDatacenter().getSimulation().clock());
        for (final Vm vm : vmsToMigrate) {
            findHostForVm(vm, overloadedHosts).ifPresent(host -> addVmToMigrationMap(migrationMap, vm, host, "\t%s will be migrated to %s"));
        }
        Log.printLine();

        return migrationMap;
    }

    /**
     * Gets a new placement for VMs from an underloaded host.
     *
     * @param vmsToMigrate the list of VMs to migrate from the underloaded Host
     * @param excludedHosts the list of hosts that aren't selected as
     * destination hosts
     * @return the new vm placement for the given VMs
     */
    protected Map<Vm, Host> getNewVmPlacementFromUnderloadedHost(
            final List<? extends Vm> vmsToMigrate,
            final Set<? extends Host> excludedHosts)
    {
        final Map<Vm, Host> migrationMap = new HashMap<>();
        VmList.sortByCpuUtilization(vmsToMigrate, getDatacenter().getSimulation().clock());
        for (final Vm vm : vmsToMigrate) {
            //try to find a target Host to place a VM from an underloaded Host that is not underloaded too
            final Optional<Host> optional = findHostForVm(vm, excludedHosts, host -> !isHostUnderloaded(host));
            if (!optional.isPresent()) {
                Log.printFormattedLine("\tA new Host, which isn't also underloaded or won't be overloaded, couldn't be found to migrate %s.", vm);
                Log.printFormattedLine("\tMigration of VMs from the underloaded %s cancelled.", vm.getHost());
                return new HashMap<>();
            }
            addVmToMigrationMap(migrationMap, vm, optional.get(), "\t%s will be allocated to %s");
        }

        return migrationMap;
    }

    private <T extends Host> void addVmToMigrationMap(final Map<Vm, T> migrationMap, final Vm vm, final T targetHost, final String msg) {
        /*
        Temporarily creates the VM into the target Host so that
        when the next VM is got to be migrated, if the same Host
        is selected as destination, the resource to be
        used by the previous VM will be considered when
        assessing the suitability of such a Host for the next VM.
         */
        targetHost.createTemporaryVm(vm);
        Log.printFormattedLine(msg, vm, targetHost);
        migrationMap.put(vm, targetHost);
    }

    /**
     * Gets the VMs to migrate from Hosts.
     *
     * @param overloadedHosts the List of overloaded Hosts
     * @return the VMs to migrate from hosts
     */
    protected List<Vm> getVmsToMigrateFromOverloadedHosts(final Set<Host> overloadedHosts) {
        final List<Vm> vmsToMigrate = new LinkedList<>();
        for (final Host host : overloadedHosts) {
            vmsToMigrate.addAll(getVmsToMigrateFromOverloadedHost(host));
        }

        return vmsToMigrate;
    }

    private List<Vm> getVmsToMigrateFromOverloadedHost(final Host host) {
        final List<Vm> vmsToMigrate = new LinkedList<>();
        while (true) {
            final Vm vm = getVmSelectionPolicy().getVmToMigrate(host);
            if (Vm.NULL == vm) {
                break;
            }
            vmsToMigrate.add(vm);
            /*Temporarily destroys the selected VM into the overloaded Host so that
            the loop gets VMs from such a Host until it is not overloaded anymore.*/
            host.destroyTemporaryVm(vm);
            if (!isHostOverloaded(host)) {
                break;
            }
        }

        return vmsToMigrate;
    }

    /**
     * Gets the VMs to migrate from under utilized host.
     *
     * @param host the host
     * @return the vms to migrate from under utilized host
     */
    protected List<? extends Vm> getVmsToMigrateFromUnderUtilizedHost(final Host host) {
        return host.getVmList().stream()
            .filter(vm -> !vm.isInMigration())
            .collect(Collectors.toCollection(LinkedList::new));
    }

    /**
     * Gets the switched off hosts.
     *
     * @return the switched off hosts
     */
    protected List<Host> getSwitchedOffHosts() {
        return this.getHostList().stream()
            .filter(host -> !host.isActive() || host.isFailed())
            .collect(toList());
    }

    /**
     * Gets the List of overloaded hosts.
     * If a Host is overloaded but it has VMs migrating out,
     * then it's not included in the returned List
     * because the VMs to be migrated to move the Host from
     * the overload state already are in migration.
     *
     * @return the over utilized hosts
     */
    protected Set<Host> getOverloadedHosts() {
        return this.getHostList().stream()
            .filter(this::isHostOverloaded)
            .filter(h -> h.getVmsMigratingOut().isEmpty())
            .collect(toSet());
    }

    /**
     * Gets the most underloaded Host.
     * If a Host is underloaded but it has VMs migrating in,
     * then it's not included in the returned List
     * because the VMs to be migrated to move the Host from
     * the underload state already are in migration to it.
     * Likewise, if all VMs are migrating out, nothing has to be
     * done anymore. It just has to wait the VMs to finish
     * the migration.
     *
     * @param excludedHosts the Hosts that have to be ignored when looking for the under utilized Host
     * @return the most under utilized host or {@link Host#NULL} if no Host is found
     */
    private Host getUnderloadedHost(final Set<? extends Host> excludedHosts) {
        return this.getHostList().stream()
            .filter(h -> !excludedHosts.contains(h))
            .filter(Host::isActive)
            .filter(this::isHostUnderloaded)
            .filter(h -> h.getVmsMigratingIn().isEmpty())
            .filter(this::isNotAllVmsMigratingOut)
            .min(Comparator.comparingDouble(Host::getUtilizationOfCpu))
            .orElse(Host.NULL);
    }

    /**
     * Checks if a host is under utilized, based on current CPU usage.
     *
     * @param host the host
     * @return true, if the host is under utilized; false otherwise
     */
    @Override
    public boolean isHostUnderloaded(final Host host) {
        return getHostCpuUtilizationPercentage(host) < getUnderUtilizationThreshold();
    }

    /**
     * {@inheritDoc}
     * It's based on current CPU usage.
     *
     * @param host {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public boolean isHostOverloaded(final Host host) {
        final double upperThreshold = getOverUtilizationThreshold(host);
        addHistoryEntryIfAbsent(host, upperThreshold);

        return getHostCpuUtilizationPercentage(host) > upperThreshold;
    }

    private double getHostCpuUtilizationPercentage(final Host host) {
        return getHostTotalRequestedMips(host) / host.getTotalMipsCapacity();
    }

    /**
     * Gets the total MIPS that is currently being used by all VMs inside the Host.
     * @param host
     * @return
     */
    private double getHostTotalRequestedMips(final Host host) {
        return host.getVmList().stream()
            .mapToDouble(Vm::getCurrentRequestedTotalMips)
            .sum();
    }

    /**
     * Checks if all VMs of a Host are <b>NOT</b> migrating out.
     * In this case, the given Host will not be selected as an underloaded Host at the current moment.
     *
     * @param host the host to check
     * @return
     */
    protected boolean isNotAllVmsMigratingOut(final Host host) {
        return host.getVmList().stream().anyMatch(vm -> !vm.isInMigration());
    }

    /**
     * Saves the current map between a VM and the host where it is place.
     *
     * @see #savedAllocation
     */
    protected void saveAllocation() {
        savedAllocation.clear();
        for (final Host host : getHostList()) {
            for (final Vm vm : host.getVmList()) {
                if (!host.getVmsMigratingIn().contains(vm)) {
                    savedAllocation.put(vm, host);
                }
            }
        }
    }

    /**
     * Restore VM allocation from the allocation history.
     *
     * @see #savedAllocation
     */
    protected void restoreAllocation() {
        for (final Host host : getHostList()) {
            host.destroyAllVms();
            host.reallocateMigratingInVms();
        }

        for (final Vm vm : savedAllocation.keySet()) {
            final Host host = (Host) savedAllocation.get(vm);
            if (!host.createTemporaryVm(vm)) {
                Log.printFormattedLine("Couldn't restore %s on %s", vm, host);
                return;
            }
        }
    }

    /**
     * Gets the power consumption of a host after the supposed placement of a candidate VM.
     * The VM is not in fact placed at the host.
     *
     * @param host the host to check the power consumption
     * @param vm the candidate vm
     *
     * @return the host power consumption after the supposed VM placement or 0 if the power
     * consumption could not be determined
     */
    protected double getPowerAfterAllocation(final Host host, final Vm vm) {
        try {
            return host.getPowerModel().getPower(getMaxUtilizationAfterAllocation(host, vm));
        } catch (Exception e) {
            Log.printFormattedLine("[ERROR] Power consumption for Host %d could not be determined: ", host.getId(), e.getMessage());
        }

        return 0;
    }

    /**
     * Gets the max power consumption of a host after placement of a candidate
     * VM. The VM is not in fact placed at the host. We assume that load is
     * balanced between PEs. The only restriction is: VM's max MIPS < PE's MIPS
     *
     * @param host the host
     * @param vm the vm
     *
     * @return the power after allocation
     */
    protected double getMaxUtilizationAfterAllocation(final Host host, final Vm vm) {
        final double requestedTotalMips = vm.getCurrentRequestedTotalMips();
        final double hostUtilizationMips = getUtilizationOfCpuMips(host);
        final double hostPotentialMipsUse = hostUtilizationMips + requestedTotalMips;
        return hostPotentialMipsUse / host.getTotalMipsCapacity();
    }

    /**
     * Gets the utilization of the CPU in MIPS for the current potentially
     * allocated VMs.
     *
     * @param host the host
     *
     * @return the utilization of the CPU in MIPS
     */
    protected double getUtilizationOfCpuMips(final Host host) {
        double hostUtilizationMips = 0;
        for (final Vm vm2 : host.getVmList()) {
            if (host.getVmsMigratingIn().contains(vm2)) {
                // calculate additional potential CPU usage of a migrating in VM
                hostUtilizationMips += host.getTotalAllocatedMipsForVm(vm2) * 0.9 / 0.1;
            }
            hostUtilizationMips += host.getTotalAllocatedMipsForVm(vm2);
        }
        return hostUtilizationMips;
    }

    /**
     * Adds an entry for each history map of a host if it doesn't contain
     * an entry for the current simulation time.
     *
     * @param host the host to add metric history entries
     * @param metric the metric to be added to the metric history map
     */
    protected void addHistoryEntryIfAbsent(final Host host, final double metric) {
        timeHistory.putIfAbsent(host, new LinkedList<>());
        utilizationHistory.putIfAbsent(host, new LinkedList<>());
        metricHistory.putIfAbsent(host, new LinkedList<>());

        final Simulation simulation = host.getSimulation();
        if (!timeHistory.get(host).contains(simulation.clock())) {
            timeHistory.get(host).add(simulation.clock());
            utilizationHistory.get(host).add(host.getUtilizationOfCpu());
            metricHistory.get(host).add(metric);
        }
    }

    /**
     * Gets the saved allocation.
     *
     * @return the saved allocation
     */
    protected Map<Vm, Host> getSavedAllocation() {
        return savedAllocation;
    }

    /**
     * Sets the vm selection policy.
     *
     * @param vmSelectionPolicy the new vm selection policy
     */
    protected final void setVmSelectionPolicy(final PowerVmSelectionPolicy vmSelectionPolicy) {
        Objects.requireNonNull(vmSelectionPolicy);
        this.vmSelectionPolicy = vmSelectionPolicy;
    }

    /**
     * Gets the vm selection policy.
     *
     * @return the vm selection policy
     */
    protected PowerVmSelectionPolicy getVmSelectionPolicy() {
        return vmSelectionPolicy;
    }

    @Override
    public Map<Host, List<Double>> getUtilizationHistory() {
        return Collections.unmodifiableMap(utilizationHistory);
    }

    @Override
    public Map<Host, List<Double>> getMetricHistory() {
        return Collections.unmodifiableMap(metricHistory);
    }

    @Override
    public Map<Host, List<Double>> getTimeHistory() {
        return Collections.unmodifiableMap(timeHistory);
    }

    @Override
    public double getUnderUtilizationThreshold() {
        return underUtilizationThreshold;
    }

    @Override
    public void setUnderUtilizationThreshold(final double underUtilizationThreshold) {
        this.underUtilizationThreshold = underUtilizationThreshold;
    }
}
