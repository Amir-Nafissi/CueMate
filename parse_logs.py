import json
import re

def analyze_all_metrics(filename):
    print(f"--- {filename} ---")
    
    with open(filename, 'r', encoding='utf-8') as f:
        content = f.read()
        
    # Smile Score: ... (relWidth=..., mouthWidth=..., faceWidth=...)
    rel_widths = re.findall(r'relWidth\\u003d([-\d\.E]+)', content)
    if not rel_widths:
        rel_widths = re.findall(r'relWidth=([-\d\.E]+)', content)
    if rel_widths:
        rel_widths = [float(x) for x in rel_widths]
        print(f"Smile relWidth -> Min: {min(rel_widths):.5f}, Max: {max(rel_widths):.5f}, Avg: {sum(rel_widths)/len(rel_widths):.5f}")

    # Surprise Score: ... (relMouth=..., relEye=...)
    rel_mouths = re.findall(r'relMouth\\u003d([-\d\.E]+)', content)
    if not rel_mouths:
        rel_mouths = re.findall(r'relMouth=([-\d\.E]+)', content)
    if rel_mouths:
        rel_mouths = [float(x) for x in rel_mouths]
        print(f"Surprise relMouth -> Min: {min(rel_mouths):.5f}, Max: {max(rel_mouths):.5f}, Avg: {sum(rel_mouths)/len(rel_mouths):.5f}")

    rel_eyes = re.findall(r'relEye\\u003d([-\d\.E]+)', content)
    if not rel_eyes:
        rel_eyes = re.findall(r'relEye=([-\d\.E]+)', content)
    if rel_eyes:
        rel_eyes = [float(x) for x in rel_eyes]
        print(f"Surprise relEye -> Min: {min(rel_eyes):.5f}, Max: {max(rel_eyes):.5f}, Avg: {sum(rel_eyes)/len(rel_eyes):.5f}")

    # Frown Score: ... (relDrop=..., relOpen=...)
    rel_drops = re.findall(r'relDrop\\u003d([-\d\.E]+)', content)
    if not rel_drops:
        rel_drops = re.findall(r'relDrop=([-\d\.E]+)', content)
    if rel_drops:
        rel_drops = [float(x) for x in rel_drops]
        print(f"Frown relDrop -> Min: {min(rel_drops):.5f}, Max: {max(rel_drops):.5f}, Avg: {sum(rel_drops)/len(rel_drops):.5f}")

    rel_opens = re.findall(r'relOpen\\u003d([-\d\.E]+)', content)
    if not rel_opens:
        rel_opens = re.findall(r'relOpen=([-\d\.E]+)', content)
    if rel_opens:
        rel_opens = [float(x) for x in rel_opens]
        print(f"Frown relOpen (mouth open) -> Min: {min(rel_opens):.5f}, Max: {max(rel_opens):.5f}, Avg: {sum(rel_opens)/len(rel_opens):.5f}")


analyze_all_metrics('upset_logcat.logcat')
