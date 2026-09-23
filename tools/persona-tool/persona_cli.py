#!/usr/bin/env python3
"""
Dialex Persona CLI Tool
-----------------------
Utility to generate, validate, and bundle personas for the Dialex platform.

Commands:
  generate  - Interactively create a new persona JSON file.
  validate  - Check persona JSON files or bundled collections for schema validity.
  bundle    - Combine multiple individual persona JSON files into a single bundled JSON.
"""

import argparse
import json
import os
import sys
import re

REQUIRED_FIELDS = ["id", "name", "description", "category", "role", "systemPrompt"]
OPTIONAL_SPEC_FIELDS = ["roleAndPersona", "coreExpertise", "toneAndVoice", "objective"]
ALL_FIELDS = REQUIRED_FIELDS + OPTIONAL_SPEC_FIELDS + ["icon", "caveman", "ponytail", "isSystem"]

VALID_ICONS = [
    "code", "database", "security", "terminal", "analytics", "science",
    "psychology", "business", "legal", "creative", "default"
]

def sanitize_id(name: str) -> str:
    cleaned = re.sub(r'[^a-zA-Z0-9]+', '_', name.strip().lower()).strip('_')
    return cleaned or "custom_persona"

def validate_persona(persona: dict, filename: str = "") -> list:
    errors = []
    prefix = f"[{filename}] " if filename else ""

    for field in REQUIRED_FIELDS:
        if field not in persona or not str(persona[field]).strip():
            errors.append(f"{prefix}Missing required field: '{field}'")

    return errors

def cmd_generate(args):
    print("=== Dialex Persona Interactive Generator ===")
    print("Fill in the fields below. Press Enter to use defaults where provided.\n")

    name = input("Persona Name (e.g. 'Senior Distributed Systems Architect'): ").strip()
    if not name:
        print("Error: Name cannot be empty.")
        sys.exit(1)

    default_id = sanitize_id(name)
    persona_id = input(f"ID [{default_id}]: ").strip() or default_id

    category = input("Category (e.g. 'Software Engineering', 'Philosophy') [Custom]: ").strip() or "Custom"
    role = input(f"Role title [{name}]: ").strip() or name
    description = input("Short Description: ").strip()
    if not description:
        description = f"{role} focusing on {category} deliberative analysis."

    print("\n--- Optional Structured Persona Specifications ---")
    role_and_persona = input("Role & Persona (e.g. 'Pragmatic distributed systems architect'): ").strip()
    core_expertise = input("Core Expertise (e.g. 'Raft consensus, CAP theorem, fault tolerance'): ").strip()
    tone_and_voice = input("Tone & Voice (e.g. 'Analytical, rigorous, concise'): ").strip()
    objective = input("Objective (e.g. 'Prevent architectural degradation and ensure fault tolerance'): ").strip()

    print("\n--- System Prompt / Context ---")
    prompt_input = input("Enter System Prompt (or leave blank to auto-compose from specifications): ").strip()
    
    if not prompt_input:
        prompt_parts = []
        if role_and_persona:
            prompt_parts.append(f"ROLE & PERSONA:\n{role_and_persona}")
        if core_expertise:
            prompt_parts.append(f"CORE EXPERTISE:\n{core_expertise}")
        if tone_and_voice:
            prompt_parts.append(f"TONE & VOICE:\n{tone_and_voice}")
        if objective:
            prompt_parts.append(f"OBJECTIVE:\n{objective}")
        
        prompt_input = "\n\n".join(prompt_parts) if prompt_parts else f"You are {name}, acting as {role}."

    print("\nAvailable icons:", ", ".join(VALID_ICONS))
    icon = input("Icon [code]: ").strip() or "code"

    persona_data = {
        "id": persona_id,
        "name": name,
        "description": description,
        "category": category,
        "role": role,
        "roleAndPersona": role_and_persona,
        "coreExpertise": core_expertise,
        "toneAndVoice": tone_and_voice,
        "objective": objective,
        "systemPrompt": prompt_input,
        "icon": icon,
        "caveman": False,
        "ponytail": False,
        "isSystem": False
    }

    output_file = args.output or f"{persona_id}.json"
    with open(output_file, "w", encoding="utf-8") as f:
        json.dump(persona_data, f, indent=2, ensure_ascii=False)

    print(f"\nSuccessfully generated persona: {output_file}")

def cmd_validate(args):
    target = args.path
    if not os.path.exists(target):
        print(f"Error: Path '{target}' does not exist.")
        sys.exit(1)

    files_to_check = []
    if os.path.isdir(target):
        for root, _, files in os.walk(target):
            for file in files:
                if file.endswith(".json"):
                    files_to_check.append(os.path.join(root, file))
    else:
        files_to_check.append(target)

    all_errors = []
    total_personas = 0

    for file_path in files_to_check:
        try:
            with open(file_path, "r", encoding="utf-8") as f:
                data = json.load(f)
                if isinstance(data, list):
                    for idx, item in enumerate(data):
                        total_personas += 1
                        errs = validate_persona(item, f"{file_path}#{idx}")
                        all_errors.extend(errs)
                elif isinstance(data, dict):
                    if "personas" in data and isinstance(data["personas"], list):
                        for idx, item in enumerate(data["personas"]):
                            total_personas += 1
                            errs = validate_persona(item, f"{file_path}#personas[{idx}]")
                            all_errors.extend(errs)
                    else:
                        total_personas += 1
                        errs = validate_persona(data, file_path)
                        all_errors.extend(errs)
        except Exception as e:
            all_errors.append(f"Failed to parse {file_path}: {e}")

    if all_errors:
        print(f"Validation failed with {len(all_errors)} error(s):")
        for err in all_errors:
            print(f"  - {err}")
        sys.exit(1)
    else:
        print(f"Validated {len(files_to_check)} file(s), {total_personas} persona(s). All valid!")

def cmd_bundle(args):
    input_dir = args.input_dir
    output_file = args.output

    if not os.path.isdir(input_dir):
        print(f"Error: Directory '{input_dir}' not found.")
        sys.exit(1)

    personas = []
    seen_ids = set()

    for file_name in sorted(os.listdir(input_dir)):
        if file_name.endswith(".json") and file_name != os.path.basename(output_file):
            path = os.path.join(input_dir, file_name)
            try:
                with open(path, "r", encoding="utf-8") as f:
                    content = json.load(f)
                    items = content if isinstance(content, list) else [content]
                    for item in items:
                        p_id = item.get("id")
                        if p_id in seen_ids:
                            print(f"Warning: Duplicate ID '{p_id}' found in {file_name}, skipping.")
                            continue
                        seen_ids.add(p_id)
                        personas.append(item)
            except Exception as e:
                print(f"Error reading {file_name}: {e}")

    with open(output_file, "w", encoding="utf-8") as f:
        json.dump(personas, f, indent=2, ensure_ascii=False)

    print(f"Bundled {len(personas)} persona(s) into '{output_file}'.")

def clean_json_text(text: str) -> str:
    cleaned = text.strip()
    match = re.search(r'```(?:json)?\s*(\{.*?\}|\[.*?\])\s*```', cleaned, re.DOTALL)
    if match:
        return match.group(1).strip()
    if cleaned.startswith("```"):
        lines = cleaned.split("\n")
        cleaned = "\n".join(lines[1:-1] if lines[-1].strip().startswith("```") else lines[1:])
    return cleaned.strip()

def cmd_import(args):
    raw_content = ""
    if args.file:
        if not os.path.exists(args.file):
            print(f"Error: File '{args.file}' does not exist.")
            sys.exit(1)
        with open(args.file, "r", encoding="utf-8") as f:
            raw_content = f.read()
    elif args.json:
        raw_content = args.json
    else:
        print("=== Paste Persona JSON (or AI markdown output). Press Ctrl+D (or Ctrl+Z on Windows) when done ===")
        try:
            raw_content = sys.stdin.read()
        except KeyboardInterrupt:
            print("\nAborted.")
            sys.exit(1)

    cleaned = clean_json_text(raw_content)
    if not cleaned:
        print("Error: Empty JSON content.")
        sys.exit(1)

    try:
        data = json.loads(cleaned)
    except json.JSONDecodeError as e:
        print(f"Error: Failed to parse JSON: {e}")
        sys.exit(1)

    items = data if isinstance(data, list) else [data]
    saved_files = []

    for idx, item in enumerate(items):
        name = item.get("name", "").strip()
        p_id = item.get("id", "").strip() or sanitize_id(name or f"persona_{idx+1}")
        item["id"] = p_id
        if "isSystem" not in item:
            item["isSystem"] = False
        if "category" not in item:
            item["category"] = "General Debate"
        if "role" not in item:
            item["role"] = name
        if "caveman" not in item:
            item["caveman"] = False
        if "ponytail" not in item:
            item["ponytail"] = False

        # If systemPrompt is missing, compose from attributes
        if not item.get("systemPrompt"):
            parts = []
            if item.get("roleAndPersona"):
                parts.append(f"### ROLE & PERSONA\n{item['roleAndPersona']}")
            if item.get("coreExpertise"):
                parts.append(f"### CORE EXPERTISE\n{item['coreExpertise']}")
            if item.get("toneAndVoice"):
                parts.append(f"### TONE & VOICE\n{item['toneAndVoice']}")
            if item.get("objective"):
                parts.append(f"### OBJECTIVE\n{item['objective']}")
            item["systemPrompt"] = "\n\n".join(parts) or f"You are {name}."

        errs = validate_persona(item)
        if errs:
            print(f"Validation warnings for persona '{name}':")
            for e in errs:
                print(f"  - {e}")

        out_path = args.output or f"{p_id}.json"
        if len(items) > 1 and not args.output:
            out_path = f"{p_id}.json"

        with open(out_path, "w", encoding="utf-8") as f:
            json.dump(item, f, indent=2, ensure_ascii=False)
        saved_files.append(out_path)

    print(f"Successfully imported {len(items)} persona(s): {', '.join(saved_files)}")

def cmd_ai_prompt(args):
    prompt = """You are the Dialex Persona Architect. Create a rich, specialized AI debate persona in JSON format.

A Dialex Persona has these fields:
- "name": Concise distinctive name (e.g. "Socratic Epistemologist", "Fault-Tolerant Architect")
- "category": Profession domain (e.g. "Software Engineering", "Scientific Research", "Product & Strategy", "Legal & Governance", "General Debate")
- "role": Specific functional role (e.g. "Epistemic Auditor", "Chaos Engineer")
- "description": 1-2 sentence debate mission summary
- "icon": Keyword: code | database | security | terminal | analytics | science | psychology | business | legal | creative | default
- "roleAndPersona": Background, perspective, worldview
- "coreExpertise": Specific frameworks, methodologies, trade-offs
- "toneAndVoice": Directives for tone (e.g. "Incisive, empirical, skeptical of hype")
- "objective": Fiduciary goal or mandate in debates
- "systemPrompt": Full composed prompt instructions
- "caveman": boolean (true for ultra-terse, compressed telegraphic reasoning)
- "ponytail": boolean (true for structured, bullet-driven executive takeaways)

Output ONLY valid JSON wrapped in a ```json code block so it can be loaded directly into the Dialex Persona Editor.
"""
    print(prompt)

def main():
    parser = argparse.ArgumentParser(description="Dialex Persona Tooling CLI")
    subparsers = parser.add_subparsers(dest="command", required=True)

    # generate
    p_gen = subparsers.add_parser("generate", help="Create a persona JSON interactively")
    p_gen.add_argument("-o", "--output", help="Output file path (default: <id>.json)")
    p_gen.set_defaults(func=cmd_generate)

    # import
    p_imp = subparsers.add_parser("import", help="Import persona from JSON string, file, or clipboard")
    p_imp.add_argument("-f", "--file", help="Path to JSON file to import")
    p_imp.add_argument("-j", "--json", help="Raw JSON string or markdown block")
    p_imp.add_argument("-o", "--output", help="Output file path (default: <id>.json)")
    p_imp.set_defaults(func=cmd_import)

    # ai-prompt
    p_aip = subparsers.add_parser("ai-prompt", help="Display the AI prompt template for crafting personas")
    p_aip.set_defaults(func=cmd_ai_prompt)

    # validate
    p_val = subparsers.add_parser("validate", help="Validate a JSON file or directory")
    p_val.add_argument("path", help="Path to JSON file or directory of JSON files")
    p_val.set_defaults(func=cmd_validate)

    # bundle
    p_bun = subparsers.add_parser("bundle", help="Bundle individual persona JSONs into one file")
    p_bun.add_argument("input_dir", help="Directory containing persona JSON files")
    p_bun.add_argument("-o", "--output", default="bundled_personas.json", help="Output bundled JSON file")
    p_bun.set_defaults(func=cmd_bundle)

    args = parser.parse_args()
    args.func(args)

if __name__ == "__main__":
    main()

