#!/usr/bin/env python3

import os
import sys
import argparse
import fnmatch
from pathlib import Path

def should_exclude_file(file_path):
    """
    Check if a file should be excluded based on common patterns for generated/build files
    """
    file_str = str(file_path)
    
    # Exclude build directories, generated files, and example folders
    exclude_patterns = [
        '*/build/*',
        '*/generated/*',
        '*/.gradle/*',
        '*/gradlew*',
        '*/gradle-wrapper.*',
        '*/example/*',
        '**/R.java',
        '**/R.kt',
        '**/BuildConfig.java',
        '**/BuildConfig.kt',
        '**/Manifest.java'
    ]
    
    for pattern in exclude_patterns:
        if fnmatch.fnmatch(file_str, pattern):
            return True
    
    return False

def find_java_kotlin_gradle_files(search_paths, root_path):
    """
    Find all Java, Kotlin, and Gradle files in the specified search paths
    """
    files = []
    
    for search_path in search_paths:
        full_path = Path(root_path) / search_path
        if not full_path.exists():
            print(f"Warning: Path {full_path} does not exist, skipping...")
            continue
            
        # Find Java files
        for java_file in full_path.rglob("*.java"):
            if java_file.is_file() and not should_exclude_file(java_file):
                files.append(java_file)
        
        # Find Kotlin files
        for kotlin_file in full_path.rglob("*.kt"):
            if kotlin_file.is_file() and not should_exclude_file(kotlin_file):
                files.append(kotlin_file)
        
        # Find Gradle files
        for gradle_file in full_path.rglob("*.gradle"):
            if gradle_file.is_file() and not should_exclude_file(gradle_file):
                files.append(gradle_file)
                
        # Find Gradle Kotlin DSL files
        for gradle_kts_file in full_path.rglob("*.gradle.kts"):
            if gradle_kts_file.is_file() and not should_exclude_file(gradle_kts_file):
                files.append(gradle_kts_file)
    
    return sorted(files)

def write_file_contents(files, output_file, root_path):
    """
    Write the contents of all files to the output file
    """
    with open(output_file, 'w', encoding='utf-8') as output:
        for file_path in files:
            try:
                # Calculate relative path from root
                relative_path = file_path.relative_to(root_path)
                
                # Write file header
                output.write(f"\n---\n")
                output.write(f"File: project_folder/{relative_path}\n")
                output.write(f"---\n\n")
                
                # Write file contents
                with open(file_path, 'r', encoding='utf-8', errors='replace') as f:
                    output.write(f.read())
                    
                output.write(f"\n\n")
                
                print(f"Processed: {relative_path}")
                
            except Exception as e:
                print(f"Error processing {file_path}: {e}")

def main():
    parser = argparse.ArgumentParser(
        description='Collect Java, Kotlin, and Gradle file contents into a single file',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog='''
Examples:
  python collect_java_gradle.py
  python collect_java_gradle.py --path "android/src android/app" --output java_kotlin_gradle_contents.txt
  python collect_java_gradle.py -p "android ios" -o mobile_files.txt
        '''
    )
    
    parser.add_argument(
        '-p', '--path',
        default='android',
        help='Space-separated paths to search for Java/Kotlin/Gradle files (default: "android")'
    )
    
    parser.add_argument(
        '-o', '--output',
        default='all_java_kotlin_gradle_contents.txt',
        help='Output file name (default: "all_java_kotlin_gradle_contents.txt")'
    )
    
    args = parser.parse_args()
    
    # Get root path from environment variable or use current directory
    root_path = Path(os.environ.get('MELOS_ROOT_PATH', '.'))
    
    # Parse search paths
    search_paths = args.path.split()
    
    print(f"Root path: {root_path.absolute()}")
    print(f"Searching for Java/Kotlin/Gradle files in: {' '.join(search_paths)}")
    print(f"Output will be written to: {args.output}")
    
    # Find all Java, Kotlin, and Gradle files
    files = find_java_kotlin_gradle_files(search_paths, root_path)
    
    if not files:
        print("No Java, Kotlin, or Gradle files found!")
        return
    
    print(f"Found {len(files)} files to process")
    
    # Write contents to output file
    output_path = root_path / args.output
    write_file_contents(files, output_path, root_path)
    
    print(f"Java/Kotlin/Gradle contents have been written to {args.output}")

if __name__ == "__main__":
    main() 