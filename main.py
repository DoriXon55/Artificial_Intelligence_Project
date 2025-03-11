import whisper
#sluzy do wydobywania tekstu z plików audio
from transformers import pipeline

def main():
    model = whisper.load_model("base") #do wyboru tiny, small, medium, larger, base
    #zasadniczo różnią się ilośćią przetworzonych danych, im większy model tym lepsza jakość, ale dłuższy czas przetwarzania
    result = model.transcribe("Path")
    #audio na tekst - logiczne
    print(result["text"])

    # limit tekstu aby nie byl wyzszy niz ilosc tokenow (1024)
    text = result["text"]
    max_words = 200  # ilosc slow aby pozostac pod limitem (okolo 200)
    
    # Take text from beginning, middle and end for better context
    words = text.split()
    if len(words) > 600:  # if text is long enough
        beginning = " ".join(words[:100])
        middle = " ".join(words[len(words)//2-50:len(words)//2+50])
        end = " ".join(words[-100:])
        truncated_text = beginning + " ... " + middle + " ... " + end
    else:
        truncated_text = " ".join(words[:200])
        
    print("\nUsing truncated text for summary...")
    
    # Use a more powerful summarization modeldel
    summarizer = pipeline("summarization", model="facebook/bart-large-cnn")
    
    #TO DO: FIX SUMMARY. IT PRINTS THE BEGGINING OF PROVIDED TEXT

    # Add instructions for better summarization
    instruction = "Please summarize the following speech into a few sentences: "
    truncated_text_with_instruction = instruction + truncated_text
    summary = summarizer(truncated_text_with_instruction, max_length=100, min_length=30, do_sample=False)
    print(summary[0]["summary_text"])
    #podsumowanie tekstu - max_lenght to maksymalna długość podsumowania, min_lenght to minimalna długość podsumowania,
    #do_sample to czy ma być losowe podsumowanie

if __name__ == "__main__":
    main()
