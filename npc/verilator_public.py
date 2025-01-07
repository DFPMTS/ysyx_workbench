import re


def add_verilator_public_comment(verilog_code):
    """
    Modify Verilog code to add `/*verilator public*/` after the variable name
    in all wire declarations whose names do not start with an underscore.

    Args:
        verilog_code (str): The original Verilog code as a string.

    Returns:
        str: The modified Verilog code.
    """
    # Regular expression to match wire declarations
    # Matches: `wire <name>` or `wire [<range>] <name>`
    # Ensures the wire name does not start with an underscore
    wire_pattern = re.compile(
        r"""
        \bwire\b             # Match the keyword 'wire'
        (\s*\[[^]]*\])?      # Optional: Match a range (e.g., [7:0])
        \s+                  # Match whitespace
        ([a-zA-Z]\w*)        # Match a wire name that does not start with an underscore
        \b                   # Word boundary to ensure we match the full name
        """,
        re.VERBOSE,
    )

    # Function to insert the comment after the variable name
    def insert_comment(match):
        # Extract the matched groups
        wire_declaration = match.group(
            0
        )  # Full wire declaration (e.g., "wire [7:0] data_bus")
        wire_name = match.group(2)  # The wire name (e.g., "data_bus")

        # Find the position of the wire name in the declaration
        name_start = wire_declaration.find(wire_name)
        name_end = name_start + len(wire_name)

        # Insert the comment right after the variable name
        return f"{wire_declaration[:name_end]} /*verilator public_flat*/{wire_declaration[name_end:]}"

    # Replace all matched wire declarations in the Verilog code
    modified_code = wire_pattern.sub(insert_comment, verilog_code)
    return modified_code


# Example usage
if __name__ == "__main__":
    # Input and output file paths
    input_files = ["vsrc/Core.sv", "vsrc/CSR.sv"]

    for input_file in input_files:
        with open(input_file, "r") as f:
            verilog_code = f.read()
        modified_verilog_code = add_verilator_public_comment(verilog_code)
        with open(input_file, "w") as f:
            f.write(modified_verilog_code)
        print(f"Modified Verilog code has been written to {input_file}")
