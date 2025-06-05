import subprocess
import tempfile
import os

# Working GLSL compute shader example
shader_source = """
#version 450
layout(local_size_x = 32, local_size_y = 1, local_size_z = 1) in;
layout(set = 0, binding = 0) buffer DataBuffer {
float data0[];
};
void main() {
  int gidx0 = int(gl_WorkGroupID.x); /* 800 */
  int lidx0 = int(gl_LocalInvocationID.x); /* 32 */
  int alu0 = ((gidx0<<7)+(lidx0<<2));
  (data0[alu0]) = (float(alu0));
  int alu2 = (alu0+1);
  (data0[alu2]) = (float(alu2));
  int alu4 = (alu0+2);
  (data0[alu4]) = (float(alu4));
  int alu6 = (alu0+3);
  (data0[alu6]) = (float(alu6));
}
"""
def compile_shader_to_spv(glsl_source, output_spv_path):
    """Compile GLSL shader to SPIR-V binary.

    Args:
        glsl_source (str): GLSL shader source code
        output_spv_path (str): Path to output SPIR-V binary file
    """
    # Create a temporary file for the GLSL source
    with tempfile.NamedTemporaryFile(suffix=".comp", delete=False, mode="w") as temp_file:
        temp_file.write(glsl_source)
        temp_file_path = temp_file.name

    try:
        # Run glslangValidator to compile GLSL to SPIR-V
        result = subprocess.run(
            ["glslangValidator", "-V", temp_file_path, "-o", output_spv_path],
            check=True,
            capture_output=True,
            text=True
        )

        print(f"Shader compiled successfully to {output_spv_path}")

        # Print warnings if any
        if result.stderr:
            print("Compiler warnings:")
            print(result.stderr)

    except subprocess.CalledProcessError as e:
        print("Error compiling shader:")
        print("Exit code:", e.returncode)
        print("Error output:")
        print(e.stderr)
        print("Standard output:")
        print(e.stdout)
    except FileNotFoundError:
        print("Error: glslangValidator not found. Please ensure it is installed and in your PATH.")
        print("You can install it as part of the Vulkan SDK: https://vulkan.lunarg.com/")
    finally:
        # Clean up the temporary file
        try:
            os.unlink(temp_file_path)
        except OSError as e:
            print(f"Warning: Could not delete temporary file {temp_file_path}: {e}")

if __name__ == "__main__":
    # Output path for the compiled SPIR-V binary
    output_path = "app/src/main/assets/shader.comp.spv"
    compile_shader_to_spv(shader_source, output_path)
