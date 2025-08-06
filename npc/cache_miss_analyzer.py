#!/usr/bin/env python3
"""
Cache Miss Log Analyzer

This script analyzes cache miss log files with the format "cycle addr" per line.
It finds addresses that appear multiple times and reports their referenced cycle numbers.
"""

import argparse
import sys
from collections import defaultdict
from pathlib import Path


def parse_cache_miss_log(log_file_path):
    """
    Parse the cache miss log file and return a dictionary mapping addresses to cycles.
    
    Args:
        log_file_path (str): Path to the cache miss log file
        
    Returns:
        dict: Dictionary where keys are addresses and values are lists of cycle numbers
    """
    addr_cycles = defaultdict(list)
    
    try:
        with open(log_file_path, 'r') as f:
            line_num = 0
            for line in f:
                line_num += 1
                line = line.strip()
                
                # Skip empty lines
                if not line:
                    continue
                
                # Parse cycle and address
                parts = line.split()
                if len(parts) != 2:
                    print(f"Warning: Invalid line format at line {line_num}: {line}")
                    continue
                
                try:
                    cycle = int(parts[0])
                    addr = parts[1].lower()  # Normalize to lowercase
                    addr_cycles[addr].append(cycle)
                except ValueError as e:
                    print(f"Warning: Error parsing line {line_num}: {line} - {e}")
                    continue
                    
    except FileNotFoundError:
        print(f"Error: Log file '{log_file_path}' not found.")
        sys.exit(1)
    except IOError as e:
        print(f"Error reading log file: {e}")
        sys.exit(1)
        
    return dict(addr_cycles)


def analyze_repeated_addresses(addr_cycles, min_occurrences=2):
    """
    Analyze addresses that appear multiple times.
    
    Args:
        addr_cycles (dict): Dictionary mapping addresses to cycle lists
        min_occurrences (int): Minimum number of occurrences to consider
        
    Returns:
        dict: Dictionary of addresses that appear multiple times
    """
    repeated_addresses = {}
    
    for addr, cycles in addr_cycles.items():
        if len(cycles) >= min_occurrences:
            repeated_addresses[addr] = cycles
            
    return repeated_addresses


def print_analysis_results(addr_cycles, repeated_addresses):
    """
    Print the analysis results in a formatted way.
    
    Args:
        addr_cycles (dict): All address-cycle mappings
        repeated_addresses (dict): Addresses that appear multiple times
    """
    total_misses = sum(len(cycles) for cycles in addr_cycles.values())
    unique_addresses = len(addr_cycles)
    repeated_count = len(repeated_addresses)
    
    # Calculate memory footprint (each access is 32 bytes)
    total_bytes = total_misses * 32
    unique_bytes = unique_addresses * 32
    
    print("=" * 60)
    print("CACHE MISS ANALYSIS RESULTS")
    print("=" * 60)
    print(f"Total cache misses: {total_misses}")
    print(f"Total memory footprint: {total_bytes:,} bytes ({total_bytes/1024:.2f} KB, {total_bytes/(1024*1024):.2f} MB)")
    print(f"Unique addresses: {unique_addresses}")
    print(f"Unique memory footprint: {unique_bytes:,} bytes ({unique_bytes/1024:.2f} KB, {unique_bytes/(1024*1024):.2f} MB)")
    print(f"Addresses with multiple misses: {repeated_count}")
    print()
    
    if not repeated_addresses:
        print("No addresses found with multiple cache misses.")
        return
    
    # Sort by number of occurrences (descending)
    sorted_repeated = sorted(repeated_addresses.items(), 
                           key=lambda x: len(x[1]), reverse=True)
    
    print("TOP 10 ADDRESSES WITH MOST CACHE MISSES:")
    print("-" * 100)
    print(f"{'Address':<12} {'Count':<6} {'Bytes':<10} {'Min Gap':<8} {'Avg Gap':<8} {'Cycle Numbers'}")
    print("-" * 100)
    
    # Show only top 10 most frequently repeating addresses
    top_10 = sorted_repeated[:10]
    for addr, cycles in top_10:
        bytes_used = len(cycles) * 32
        sorted_cycles = sorted(cycles)
        
        # Calculate cycle gaps (re-miss intervals)
        if len(sorted_cycles) > 1:
            gaps = [sorted_cycles[i+1] - sorted_cycles[i] for i in range(len(sorted_cycles)-1)]
            min_gap = min(gaps)
            avg_gap = sum(gaps) / len(gaps)
        else:
            min_gap = 0
            avg_gap = 0
        
        cycles_str = ', '.join(map(str, sorted_cycles))
        if len(cycles_str) > 25:  # Truncate very long lists
            cycles_str = cycles_str[:22] + "..."
        
        print(f"{addr:<12} {len(cycles):<6} {bytes_used:<10} {min_gap:<8.0f} {avg_gap:<8.1f} {cycles_str}")
    
    print()
    print("DETAILED STATISTICS:")
    print("-" * 30)
    print(f"Most frequently missed address: {sorted_repeated[0][0]} ({len(sorted_repeated[0][1])} times)")
    
    # Calculate some statistics
    miss_counts = [len(cycles) for cycles in repeated_addresses.values()]
    avg_repeats = sum(miss_counts) / len(miss_counts) if miss_counts else 0
    max_repeats = max(miss_counts) if miss_counts else 0
    
    # Calculate re-miss cycle statistics
    all_gaps = []
    for cycles in repeated_addresses.values():
        if len(cycles) > 1:
            sorted_cycles = sorted(cycles)
            gaps = [sorted_cycles[i+1] - sorted_cycles[i] for i in range(len(sorted_cycles)-1)]
            all_gaps.extend(gaps)
    
    if all_gaps:
        min_remiss_cycle = min(all_gaps)
        avg_remiss_cycle = sum(all_gaps) / len(all_gaps)
        max_remiss_cycle = max(all_gaps)
    else:
        min_remiss_cycle = avg_remiss_cycle = max_remiss_cycle = 0
    
    # Calculate average misses per address (including single-occurrence addresses)
    avg_miss_per_addr = total_misses / unique_addresses if unique_addresses > 0 else 0
    
    print(f"Average misses per address (all addresses): {avg_miss_per_addr:.2f}")
    print(f"Average repeats per repeated address: {avg_repeats:.2f}")
    print(f"Maximum repeats for a single address: {max_repeats}")
    print(f"Minimum re-miss cycle gap: {min_remiss_cycle:.0f}")
    print(f"Average re-miss cycle gap: {avg_remiss_cycle:.1f}")
    print(f"Maximum re-miss cycle gap: {max_remiss_cycle:.0f}")


def save_detailed_report(repeated_addresses, output_file, total_misses, unique_addresses):
    """
    Save a detailed report to a file.
    
    Args:
        repeated_addresses (dict): Addresses that appear multiple times
        output_file (str): Output file path
        total_misses (int): Total number of cache misses
        unique_addresses (int): Total number of unique addresses
    """
    try:
        with open(output_file, 'w') as f:
            f.write("DETAILED CACHE MISS ANALYSIS REPORT\n")
            f.write("=" * 50 + "\n\n")
            
            # Overall statistics
            avg_miss_per_addr = total_misses / unique_addresses if unique_addresses > 0 else 0
            total_bytes = total_misses * 32
            unique_bytes = unique_addresses * 32
            
            # Calculate re-miss cycle statistics
            all_gaps = []
            for cycles in repeated_addresses.values():
                if len(cycles) > 1:
                    sorted_cycles = sorted(cycles)
                    gaps = [sorted_cycles[i+1] - sorted_cycles[i] for i in range(len(sorted_cycles)-1)]
                    all_gaps.extend(gaps)
            
            if all_gaps:
                min_remiss_cycle = min(all_gaps)
                avg_remiss_cycle = sum(all_gaps) / len(all_gaps)
                max_remiss_cycle = max(all_gaps)
            else:
                min_remiss_cycle = avg_remiss_cycle = max_remiss_cycle = 0
            
            f.write(f"SUMMARY STATISTICS:\n")
            f.write(f"Total cache misses: {total_misses}\n")
            f.write(f"Total memory footprint: {total_bytes:,} bytes ({total_bytes/1024:.2f} KB, {total_bytes/(1024*1024):.2f} MB)\n")
            f.write(f"Unique addresses: {unique_addresses}\n")
            f.write(f"Unique memory footprint: {unique_bytes:,} bytes ({unique_bytes/1024:.2f} KB, {unique_bytes/(1024*1024):.2f} MB)\n")
            f.write(f"Average misses per address: {avg_miss_per_addr:.2f}\n")
            f.write(f"Addresses with multiple misses: {len(repeated_addresses)}\n")
            f.write(f"Minimum re-miss cycle gap: {min_remiss_cycle:.0f}\n")
            f.write(f"Average re-miss cycle gap: {avg_remiss_cycle:.1f}\n")
            f.write(f"Maximum re-miss cycle gap: {max_remiss_cycle:.0f}\n\n")
            
            sorted_repeated = sorted(repeated_addresses.items(), 
                                   key=lambda x: len(x[1]), reverse=True)
            
            for addr, cycles in sorted_repeated:
                bytes_used = len(cycles) * 32
                f.write(f"Address: {addr}\n")
                f.write(f"Total misses: {len(cycles)}\n")
                f.write(f"Memory footprint: {bytes_used} bytes ({bytes_used/1024:.2f} KB)\n")
                f.write(f"Cycle numbers: {', '.join(map(str, sorted(cycles)))}\n")
                
                # Calculate cycle differences
                sorted_cycles = sorted(cycles)
                if len(sorted_cycles) > 1:
                    diffs = [sorted_cycles[i+1] - sorted_cycles[i] for i in range(len(sorted_cycles)-1)]
                    min_gap = min(diffs)
                    avg_gap = sum(diffs) / len(diffs)
                    f.write(f"Cycle differences: {', '.join(map(str, diffs))}\n")
                    f.write(f"Minimum re-miss cycle gap: {min_gap}\n")
                    f.write(f"Average re-miss cycle gap: {avg_gap:.2f}\n")
                
                f.write("-" * 30 + "\n\n")
                
        print(f"Detailed report saved to: {output_file}")
        
    except IOError as e:
        print(f"Error saving report: {e}")


def main():
    parser = argparse.ArgumentParser(
        description="Analyze cache miss log files to find frequently missed addresses",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python cache_miss_analyzer.py cache_miss.log
  python cache_miss_analyzer.py cache_miss.log --min-occurrences 3
  python cache_miss_analyzer.py cache_miss.log --output report.txt
        """
    )
    
    parser.add_argument('log_file', 
                       help='Path to the cache miss log file')
    parser.add_argument('--min-occurrences', '-m', 
                       type=int, default=2,
                       help='Minimum number of occurrences to report (default: 2)')
    parser.add_argument('--output', '-o', 
                       help='Save detailed report to file')
    
    args = parser.parse_args()
    
    # Validate input file
    if not Path(args.log_file).exists():
        print(f"Error: Log file '{args.log_file}' does not exist.")
        sys.exit(1)
    
    # Parse the log file
    print(f"Parsing cache miss log: {args.log_file}")
    addr_cycles = parse_cache_miss_log(args.log_file)
    
    # Analyze repeated addresses
    repeated_addresses = analyze_repeated_addresses(addr_cycles, args.min_occurrences)
    
    # Print results
    print_analysis_results(addr_cycles, repeated_addresses)
    
    # Save detailed report if requested
    if args.output:
        save_detailed_report(repeated_addresses, args.output, 
                           sum(len(cycles) for cycles in addr_cycles.values()),
                           len(addr_cycles))


if __name__ == "__main__":
    main()
