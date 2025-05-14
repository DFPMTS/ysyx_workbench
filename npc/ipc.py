import matplotlib.pyplot as plt
import argparse

def plot_ipc(data_file):
    """
    Reads cycle and instruction data from a file, calculates IPC,
    and plots IPC vs. Cycles.
    """
    cycles = []
    ipc_values = []
    raw_instructions = [] # To store raw instruction counts if needed for other analyses

    try:
        with open(data_file, 'r') as f:
            for line_number, line in enumerate(f):
                line = line.strip()
                if not line or line.startswith('#'): # Skip empty lines or comments
                    continue
                
                try:
                    parts = line.split()
                    if len(parts) != 2:
                        print(f"Warning: Skipping malformed line {line_number + 1}: '{line}'. Expected 2 values, got {len(parts)}.")
                        continue
                    
                    cycle = float(parts[0])
                    total_inst_retired = float(parts[1])

                    if cycle <= 0:
                        print(f"Warning: Skipping line {line_number + 1} due to non-positive cycle count: {cycle}. IPC cannot be calculated.")
                        continue
                    
                    cycles.append(cycle)
                    raw_instructions.append(total_inst_retired)
                    ipc_values.append(total_inst_retired / cycle)
                
                except ValueError:
                    print(f"Warning: Skipping malformed line {line_number + 1}: '{line}'. Could not parse numbers.")
                    continue
                except ZeroDivisionError:
                    # This case should be caught by cycle <= 0 check, but good to have
                    print(f"Warning: Skipping line {line_number + 1} due to cycle count of zero. IPC cannot be calculated.")
                    continue
    
    except FileNotFoundError:
        print(f"Error: Data file '{data_file}' not found.")
        return
    except Exception as e:
        print(f"An error occurred while reading or processing the file: {e}")
        return

    if not cycles or not ipc_values:
        print("No valid data points were processed. Cannot generate a plot.")
        return

    # Plotting
    plt.figure(figsize=(12, 7)) # Adjust figure size for better readability
    
    plt.plot(cycles, ipc_values, marker='o', linestyle='-', markersize=5, label='IPC')
    
    plt.title('Instructions Per Cycle (IPC) Over Time (Cycles)')
    plt.xlabel('Cycle Count')
    plt.ylabel('IPC (Instructions / Cycle)')
    plt.grid(True, which='both', linestyle='--', linewidth=0.5) # Add a grid for easier analysis
    plt.legend()
    plt.tight_layout() # Adjust plot to ensure everything fits without overlapping
    
    # Annotate min/max IPC if desired (optional)
    if ipc_values:
        min_ipc = min(ipc_values)
        max_ipc = max(ipc_values)
        avg_ipc = sum(ipc_values) / len(ipc_values)
        print(f"Data points processed: {len(ipc_values)}")
        print(f"Minimum IPC: {min_ipc:.4f}")
        print(f"Maximum IPC: {max_ipc:.4f}")
        print(f"Average IPC: {avg_ipc:.4f}")
        
        # Adding text annotation for average IPC
        plt.axhline(avg_ipc, color='r', linestyle='--', linewidth=0.8, label=f'Avg IPC: {avg_ipc:.2f}')
        plt.legend()


    print(f"Displaying plot for {len(ipc_values)} data points from '{data_file}'.")
    plt.show()

if __name__ == '__main__':
    parser = argparse.ArgumentParser(
        description='Plot IPC (Instructions Per Cycle) from a data file.',
        formatter_class=argparse.RawTextHelpFormatter,
        epilog="""
Example usage:
  python plot_ipc_script.py my_data.txt

The data file (e.g., my_data.txt) should contain two numbers per line,
separated by whitespace:
  <cycle_count> <total_instructions_retired>

Example data.txt:
  1000 500
  2000 1200
  3000 2500
  4000 3800
"""
    )
    parser.add_argument(
        'data_file', 
        type=str, 
        help='Path to the data file. Each line should contain: <cycle> <total_inst_retired>'
    )
    
    args = parser.parse_args()
    
    plot_ipc(args.data_file)