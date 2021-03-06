package server;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

import protocole.Protocole;
import protocole.ProtocoleCreator;
import utils.StringUtils;
import utils.insultes.InsultesUtils;


public class Joueur extends Thread{

	private static final int MAX_INSULTES = 3;
	private String pseudo;
	private int score;
	private PrintWriter ecriture;
	private BufferedReader lecture;
	private Server server;
	
	private boolean hasQuit; // To stop listening and stop the thread
	private boolean isWaiting;
	private int nbInsulte;

	/**
	 * Constructeur d'un joueur
	 * @param socket Socket sur laquelle sera rattaché le joueur
	 * @param server Server avec lequelle communiquera le joueur
	 */
	public Joueur(Socket socket, Server server) {
		score = 0;
		this.server = server;
		hasQuit = false;
		
		try {
			ecriture = new PrintWriter(socket.getOutputStream());
		} catch (IOException e) {
			System.out.println("(Joueur) : Obtiention outputStream de "+pseudo+" impossible.");
		}
		try {
			lecture = new BufferedReader(new InputStreamReader(socket.getInputStream()));
		} catch (IOException e) {
			System.out.println("(Joueur) : Obtiention inputStream de "+pseudo+" impossible.");
		}
		
	}

	public String getPseudo() {
		return pseudo;
	}
	public void setPseudo(String pseudo) {
		this.pseudo = pseudo;
	}
	public int getScore() {
		return score;
	}
	public void setScore(int score) {
		this.score = score;
	}
	
	public void addOnePoint() {
		score++;
	}

	@Override
	public void run() {
		try {
			while(!hasQuit){
				readFromJoueur();
			}
		} catch (IOException e) {
			System.out.println("(Joueur run) Le Joueur "+ pseudo +" est parti");
			server.removeJoueur(this);
		} catch (Exception e) {
			System.out.println("(Joueur run) Exception : "+e.toString());
		}
	}

	/** throws IOException si le client se déconnecte
	 */
	public synchronized void sendToJoueur(String msg) throws IOException{
		if(ecriture!=null){
			ecriture.println(msg);
			ecriture.flush();
			if(ecriture.checkError()){
				System.out.println("(sendToJoueur) "+pseudo+" est parti...");
				throw new IOException();
			}
		}
	}
	
	/**
	 * Methode permettant la lecture de tous les messages reçu par le joueur et de les traiter
	 */
	private void readFromJoueur() throws IOException {
		String msg = "";
		while(msg.isEmpty() || !msg.contains("/")){
			msg = lecture.readLine();
			if(msg==null) msg = "";
		}
		
		System.out.println("(SERVER) ReadFromJoueur reçoit : "+msg);
		
		String[] msgs = msg.split("/");
		String cmd = msgs[0];
		
		String username = null;
		try{
			username = msgs[1];
		} catch (ArrayIndexOutOfBoundsException exc){ }
		
		if(cmd.startsWith(Protocole.CONNEXION.name())){ // CONNEXION/user/
			
			if(username==null){
				this.sendToJoueur(ProtocoleCreator.create(Protocole.BAD_PARAMETERS));
			}
			this.setPseudo(username);
			if(!server.addJoueur(this)){
				this.sendToJoueur(ProtocoleCreator.create(Protocole.USERNAME_ALREADY_USED, username));
			}
			return;
		}
		
		if( username==null || !username.equals(pseudo) ){
			this.sendToJoueur(ProtocoleCreator.create(Protocole.BAD_PARAMETERS));
		
		} else if(cmd.startsWith(Protocole.SORT.name())){ // SORT/user/
			
			if(server.removeJoueur(this)){
				this.sendToJoueur(ProtocoleCreator.create(Protocole.BYE));
				hasQuit = true;
			}
			
		} else if(cmd.startsWith(Protocole.TROUVE.name())){ // TROUVE/user/coups/
			Session session = server.getSession();
			if(session.hasStarted() && session.isPlaying(this) && session.isInReflexion()){
				boolean itIsTheFirst = false;
				int nbCoups = -1;
				try{
					nbCoups = Integer.parseInt(msgs[2]); // msgs[2] = nbCoups
				} catch (NumberFormatException | ArrayIndexOutOfBoundsException exc){
					sendToJoueur(ProtocoleCreator.create(Protocole.BAD_PARAMETERS));
					return;
				}
				synchronized (session) {
					if(session.getVainqueurReflexion()==null && nbCoups!=-1){
						session.setVainqueurReflexion(this);
						session.setNbCoupsVainqueurReflexion(nbCoups);
						itIsTheFirst = true;
						session.notify(); // session is waiting for a solution
					}
				}
				if(itIsTheFirst){
					sendToJoueur(ProtocoleCreator.create(Protocole.TUASTROUVE));
					String ilatrouve = ProtocoleCreator.create(Protocole.ILATROUVE, pseudo, Integer.toString(nbCoups));
					server.sendToThemButThis(ilatrouve, session.getAllPlaying(), this);
				}
			}
			
		} else if(cmd.startsWith(Protocole.ENCHERE.name())){ // ENCHERE/user/coups/
			Session session = server.getSession();
			if(session.hasStarted() && session.isPlaying(this) && session.isInEnchere()){
				System.out.println("Dans le if d'encheres");
				int nbCoups = -1;
				try{
					nbCoups = Integer.parseInt(msgs[2]); // msgs[2] = nbCoups
					System.out.println(pseudo+" enchéri en "+nbCoups+" coups.");
				} catch (NumberFormatException | ArrayIndexOutOfBoundsException exc){
					sendToJoueur(ProtocoleCreator.create(Protocole.BAD_PARAMETERS));
					return;
				}
				if(nbCoups>0){
					String pseudo = session.addEncheres(new Enchere(this, nbCoups));
					if(pseudo!=null){
						sendToJoueur( ProtocoleCreator.create(Protocole.ECHEC, pseudo) );
					}
					else {
						sendToJoueur(ProtocoleCreator.create(Protocole.VALIDATION));
						String ilenchere = ProtocoleCreator.create(Protocole.NOUVELLEENCHERE,this.getPseudo(),Integer.toString(nbCoups));
						server.sendToThemButThis(ilenchere, session.getAllPlaying(), this);
					}
				} else {
					sendToJoueur(ProtocoleCreator.create(Protocole.BAD_PARAMETERS));
				}
			}

		} else if(cmd.startsWith(Protocole.SOLUTION.name())){ //SOLUTION/user/deplacement 
			Session session = server.getSession();
			if(session.hasStarted() && session.isPlaying(this) 
					&& session.isInResolution() && session.isJoueurActifResolution(this)){
				String deplacements = null;
				try{
					deplacements = msgs[2]; // msgs[2] = nbCoups
					System.out.println(pseudo+" propose sa solution : "+deplacements);
				} catch (ArrayIndexOutOfBoundsException exc){}
				if(deplacements==null){
					sendToJoueur(ProtocoleCreator.create(Protocole.BAD_PARAMETERS));
				} else {
					session.addDeplacement(deplacements.toUpperCase());
					session.sendToAllPlaying(ProtocoleCreator.create(Protocole.SASOLUTION,pseudo,deplacements));
					synchronized (session) {
						session.notify();
					}
				}
			}

		} else if(cmd.startsWith(Protocole.CHAT.name())){ //CHAT/user/message 
			Session session = server.getSession();
			if(session.hasStarted() && session.isPlaying(this)){
				String message = "";
				boolean containsInsulte = false;
				for(int i=2; i<msgs.length; i++){ // On parcours le "message" eventuellement splité s'il contient "/"
					
					String[] messageVerifInsulte = msgs[i].split(" ");
					for(String insulte : messageVerifInsulte){
						if(InsultesUtils.isAnInsulte(insulte)) {
							containsInsulte=true;
							message += StringUtils.repeat("*", insulte.length()); // replace par "******"
						} else {
							message += insulte;
						}
						message += " ";
					}
					
				}
				
				System.out.println(pseudo+" dit : "+message);
				session.sendToAllPlaying(ProtocoleCreator.create(Protocole.CHAT, msgs[1], message)); // message eventuellement modifié
				
				if(containsInsulte){
					this.addInsulte();
					server.sleep(500);
					if(getNbInsulte()>3){
						session.sendToAllPlaying(ProtocoleCreator.create(Protocole.BANNI, pseudo, pseudo +" est expulsé pour insultes !"));
					} else {
						sendToJoueur(ProtocoleCreator.create(Protocole.BEFORE_BAN, "Encore "+(MAX_INSULTES-getNbInsulte()+1)+" insultes et vous serez banni..."));
					}
				}
				
				
			}

		} else {
			this.sendToJoueur(Protocole.UNKNOWN_CMD.name());
		}

	}
	
	/**
	 * Methode permettant de tester si le joueur est toujours "en vie"
	 * @return vrai si le joueur est toujours "en vie", faux sinon
	 */
	public boolean estEnVie(){
		try{
			sendToJoueur(Protocole.PING.name());
			return true;
		} catch (IOException e){
			return false;
		}
	}
	
	public boolean hasQuit(){
		return hasQuit;
	}
	
	public String toString(){
		return pseudo;
	}

	public boolean isWaiting() {
		return isWaiting;
	}
	
	public void setIsWaiting(boolean bool){
		isWaiting = bool;
	}
	
	public int getNbInsulte(){
		return nbInsulte;
	}
	
	public void addInsulte(){
		nbInsulte++;
	}


}
