package fiji.plugin.ComDet;


import java.awt.Color;
import java.awt.Frame;
import java.util.ArrayList;

import fiji.plugin.ComDet.CDAnalysis;
import fiji.plugin.ComDet.CDDialog;
import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.plugin.PlugIn;
import ij.plugin.frame.RoiManager;
import ij.process.ImageProcessor;
import ij.text.TextWindow;

public class Detect_Particles implements PlugIn {
	

	ImagePlus imp;
	boolean bIsHyperStack;
	int nStackSize;
	CompositeImage twochannels_imp;
	ImageProcessor ip;
	Overlay SpotsPositions;
	Roi RoiActive;
	RoiManager roi_manager;
	int [] imageinfo;
	
	int [] nCurrPos;
	
	CDThread [] cdthreads;
	
	CDDialog cddlg = new CDDialog();
	CDAnalysis cd = new CDAnalysis();
	CDProgressCount cdcount = new CDProgressCount(0);

	Color colorCh1;
	Color colorCh2;
	Color colorColoc = Color.yellow;
	public TextWindow SummaryTable;  

	//launch search of particles 
	public void run(String arg) {
		
		int nFreeThread = -1;
		int nSlice = 0;
		boolean bContinue = true;
		//int nStackSize;
		
		IJ.register(Detect_Particles.class);
		
		
		cd.ptable.reset(); // erase particle table
		
		//checking whether there is any open images
		imp = IJ.getImage();
		//SpotsPositions = imp.getOverlay();
		SpotsPositions = new Overlay();
		if (imp==null)
		{
		    IJ.noImage();
		    return;
		}
		else if (imp.getType() != ImagePlus.GRAY8 && imp.getType() != ImagePlus.GRAY16 ) 
		{
		    IJ.error("8 or 16 bit greyscale image required");
		    return;
		}
	
		//ok, image seems legit. 
		//Let's get info about it	
		imageinfo = imp.getDimensions();
		
		if (!cddlg.findParticles(imageinfo[2])) return;
		
	
		
		if(cddlg.bColocalization && imageinfo[2]!=2)
		{
		    IJ.error("Colocalization analysis requires image with 2 composite color channels!");
		    return;
			
		}
		
		if(cddlg.bColocalization)
		{
			twochannels_imp = (CompositeImage) imp;
			//get colors of each channel
			twochannels_imp.setC(1);
			colorCh1 = twochannels_imp.getChannelColor();
			twochannels_imp.setC(2);
			colorCh2 = twochannels_imp.getChannelColor();			
		}
		else
		{
			colorCh1 = Color.yellow;
			colorCh2 = Color.yellow;
		}
		
		nStackSize = imp.getStackSize();
		imp.isHyperStack();
		
		//generating convolution kernel with size of PSF
		if(cddlg.bTwoChannels)
		{
			cd.initConvKernel(cddlg,0);
			cd.initConvKernel(cddlg,1);
		}
		else
		{
			cd.initConvKernel(cddlg,0);
		}
		cd.nParticlesCount = new int [nStackSize];
		//getting active roi
		RoiActive = imp.getRoi();
		
		
		
		// using only necessary number of threads	 
		if(cddlg.nThreads < nStackSize) 
			cdthreads = new CDThread[cddlg.nThreads];
		else
			cdthreads = new CDThread[nStackSize];
		
		
		IJ.showStatus("Finding particles...");
		IJ.showProgress(0, nStackSize);
		nFreeThread = -1;
		nSlice = 0;
		bContinue = true;
		
		
		while (bContinue)
		{
			//check whether reached the end of stack
			if (nSlice >= nStackSize) bContinue = false;
			else
			{
				imp.setSlice(nSlice+1);
				nCurrPos = imp.convertIndexToPosition(nSlice+1);
				ip = imp.getProcessor().duplicate();
			}
			
			if (bContinue)
			{
				//filling free threads in the beginning
				if (nSlice < cdthreads.length)
					nFreeThread = nSlice;				
				else
				//looking for available free thread
				{
					nFreeThread = -1;
					while (nFreeThread == -1)
					{
						for (int t=0; t < cdthreads.length; t++)
						{
							if (!cdthreads[t].isAlive())
							{
								nFreeThread = t;
								break;
							}
						}
						if (nFreeThread == -1)
						{
							try
							{
								Thread.currentThread();
								Thread.sleep(1);
							}
							catch(Exception e)
							{
									IJ.error(""+e);
							}
						}
					}
				}
				cdthreads[nFreeThread] = new CDThread();
				cdthreads[nFreeThread].cdsetup(ip, cd, cddlg, nCurrPos, nSlice, RoiActive, nStackSize, cdcount);
				cdthreads[nFreeThread].start();
			
			} //end of if (bContinue)
			nSlice++;
		} // end of while (bContinue)
		
		for (int t=0; t<cdthreads.length;t++)
		{
			try
			{
				cdthreads[t].join();
			}
			catch(Exception e)
			{
				IJ.error(""+e);
			}
		}
		

		IJ.showStatus("Finding particles...done.");
		if(cd.ptable.getCounter()<1)
		{
			IJ.showStatus("No particles found!");
		}
		else
		{
			Sort_Results_CD.sorting_external_silent(cd, 5, true);
			
			//in case we need to add ROIs to ROI Manager
			if(cddlg.nRoiManagerAdd>0)
			{
				roi_manager = RoiManager.getInstance();
			    if(roi_manager == null) 
			    	roi_manager = new RoiManager();
			    roi_manager.reset();
			}
			if(cddlg.nTwoChannelOption>0)			
				{addParticlesToOverlayandQuantify();}
			else
				{addParticlesToOverlay();}
			
			imp.setOverlay(SpotsPositions);
			imp.updateAndRepaintWindow();
			imp.show();
			
			if(cddlg.nTwoChannelOption>0)
				{cd.ptable.show("Results");}
			else
				{showTable();}
			if(cddlg.nRoiManagerAdd>0)
			{
				roi_manager.setVisible(true);
				roi_manager.toFront();
			}
		}

	} 
	/** analyzing colocalization between particles 
	 * and adding them to overlay in case of single or simultaneous detection **/	
	void addParticlesToOverlay()
	{
		int nCount;
		int nPatNumber;
		Roi spotROI;
		
		double [] absframe;
		double [] x;
		double [] y;
		double [] xmin;
		double [] ymin;
		double [] xmax;
		double [] ymax;
		double [] dInt;
		double [] frames;
		double [] channel;
		double [] slices;
		boolean [] colocalizations;
		double [] colocIntRatio;
		

		//coordinates
		x   = cd.ptable.getColumnAsDoubles(1);		
		y   = cd.ptable.getColumnAsDoubles(2);
		
		//
		xmin   = cd.ptable.getColumnAsDoubles(6);		
		ymin   = cd.ptable.getColumnAsDoubles(7);
		xmax   = cd.ptable.getColumnAsDoubles(8);		
		ymax   = cd.ptable.getColumnAsDoubles(9);
		dInt   = cd.ptable.getColumnAsDoubles(11);
		//absolute total number
		absframe = cd.ptable.getColumnAsDoubles(0);
		//channel
		channel   = cd.ptable.getColumnAsDoubles(3);
		//slice
		slices   = cd.ptable.getColumnAsDoubles(4);
		//frame number
		frames   = cd.ptable.getColumnAsDoubles(5);
		
		nPatNumber = x.length;
		
		if(!cddlg.bColocalization)
		{
			

			
			for(nCount = 0; nCount<nPatNumber; nCount++)
			{
				spotROI = new Roi(xmin[nCount],ymin[nCount],xmax[nCount]-xmin[nCount],ymax[nCount]-ymin[nCount]);
				spotROI.setStrokeColor(colorColoc);
				if(nStackSize==1)
					spotROI.setPosition(0);
				else
				{
					if(bIsHyperStack|| (imageinfo[2]>1))
					{
						spotROI.setPosition((int)channel[nCount],(int)slices[nCount],(int)frames[nCount]);
						imp.setPosition((int)channel[nCount],(int)slices[nCount],(int)frames[nCount]);
					}
					else
						spotROI.setPosition((int)absframe[nCount]);
				}
				spotROI.setName(String.format("d%d_ch%d_sl%d_fr%d", nCount+1,(int)channel[nCount],(int)slices[nCount],(int)frames[nCount]));
				SpotsPositions.add(spotROI);	
				if(cddlg.nRoiManagerAdd>0)
					roi_manager.addRoi(spotROI);
				
			}
		}
		else
		//analyzing colocalization
		{
						
			int nFrame;
			int nSlice;
			double dThreshold;

			double dDistance;
			double dDistanceCand;
			int nCandidate;
			//boolean bOverlap;
			/** particle (detection) number in Results Table
			 * **/
			double [] nPatN;
			/**array containing indexes of detections of colocalization
			 * **/
			double [] nColocFriend;
			/**array marking if colocalization ROI was added to ROI manager
			 * **/
			boolean [] nColocROIadded;
			
			/** Array containing ROIs of colocolized particles
			 * **/
			double [][] dColocRoiXYArray;
			
			double [] dIntermediate1;
			double [] dIntermediate2;
			//double [][] spotsArr2;			
			int i,j;
			int nColocCount;
			dThreshold = cddlg.dColocDistance;
			ArrayList<double[]> spotsCh1 = new ArrayList<double[]>();
			ArrayList<double[]> spotsCh2 = new ArrayList<double[]>();
			//ArrayList<double[]> spotsCh2;

			
			colocalizations = new boolean [nPatNumber];
			colocIntRatio = new double[nPatNumber];
			nColocFriend = new double[nPatNumber];
			nColocROIadded =new boolean[nPatNumber] ;
			dColocRoiXYArray = new double [nPatNumber][4];
			
			nPatN = new double[nPatNumber];
			cd.colocstat = new int [imageinfo[3]][imageinfo[4]];
			
			for(nCount = 0; nCount<nPatNumber; nCount++)
			{
				nPatN[nCount]=nCount+1;
				nColocROIadded[nCount]=false;
			}
			
			//filling up arrays with current frame
			for(nFrame=1; nFrame<=imageinfo[4]; nFrame++)
			{
				for(nSlice=1; nSlice<=imageinfo[3]; nSlice++)
				{
					//filling up arrays with current detections in both channels
					spotsCh1.clear();
					spotsCh2.clear();
					for(nCount = 0; nCount<nPatNumber; nCount++)
					{
						if(frames[nCount]==nFrame && slices[nCount]==nSlice)
						{
							if(channel[nCount]==1)
								spotsCh1.add(new double [] {x[nCount],y[nCount], nCount, 0, xmin[nCount],xmax[nCount],ymin[nCount],ymax[nCount], dInt[nCount],nPatN[nCount]});
							else
								spotsCh2.add(new double [] {x[nCount],y[nCount], nCount, 0, xmin[nCount],xmax[nCount],ymin[nCount],ymax[nCount], dInt[nCount],nPatN[nCount]});
						}
					}
					nColocCount = 0;
					//let's compare each particle against each					
					for(i=0;i<spotsCh1.size();i++)
					{
						//bOverlap = false;
						nCandidate = -1;
						dDistanceCand = 2.0*dThreshold;
						dIntermediate1 = spotsCh1.get(i);
						
						for(j=0;j<spotsCh2.size();j++)
						{
							if(spotsCh2.get(j)[3]==0)
							{
								dDistance = Math.sqrt(Math.pow(dIntermediate1[0]-spotsCh2.get(j)[0], 2.0)+Math.pow(dIntermediate1[1]-spotsCh2.get(j)[1], 2.0));
								if(dDistance <= dThreshold)
								{
									if(dDistance<dDistanceCand)
									{
										dDistanceCand=dDistance;
										nCandidate = j;
									}
									
								}
								
							}
						
						}
						//found colocalization
						if(nCandidate>=0)
						{
							dIntermediate2 = spotsCh2.get(nCandidate);
							dIntermediate2[3] = 1;
							spotsCh2.set(nCandidate,dIntermediate2);
							dIntermediate2[0] = 0.5*(dIntermediate2[0]+dIntermediate1[0]);
							dIntermediate2[1] = 0.5*(dIntermediate2[1]+dIntermediate1[1]);
							colocalizations[(int) dIntermediate2[2]]=true;
							colocalizations[(int) dIntermediate1[2]]=true;
							colocIntRatio[(int) dIntermediate1[2]]=dIntermediate1[8]/dIntermediate2[8];
							colocIntRatio[(int) dIntermediate2[2]]=dIntermediate2[8]/dIntermediate1[8];
							double xminav=0.5*(dIntermediate1[4]+dIntermediate2[4]);
							double xmaxav=0.5*(dIntermediate1[5]+dIntermediate2[5]);
							double yminav=0.5*(dIntermediate1[6]+dIntermediate2[6]);
							double ymaxav=0.5*(dIntermediate1[7]+dIntermediate2[7]);
							//store detection indexes
							nColocFriend[(int)dIntermediate1[9]-1]=dIntermediate2[9];
							nColocFriend[(int)dIntermediate2[9]-1]=dIntermediate1[9];
							//store ROI coordinates to add to ROI manager later
							dColocRoiXYArray[(int)dIntermediate2[9]-1][0]=xminav;
							dColocRoiXYArray[(int)dIntermediate2[9]-1][1]=yminav;
							dColocRoiXYArray[(int)dIntermediate2[9]-1][2]=xmaxav-xminav;
							dColocRoiXYArray[(int)dIntermediate2[9]-1][3]=ymaxav-yminav;
							for(j=1; j<3; j++)
							{
							
								spotROI = new Roi(xminav,yminav,xmaxav-xminav,ymaxav-yminav);
								
								spotROI.setStrokeColor(colorColoc);
								spotROI.setPosition(j,nSlice,nFrame);
								
								if(cddlg.nRoiManagerAdd>0)
								{
									//adding just one here, since ROI Manager checks for duplication
									if(j==1)
									{
										spotROI.setName(String.format("d%d_ch%d_sl%d_fr%d_c1", (int)dIntermediate1[9],j,nSlice,nFrame));
																
										
										imp.setPosition(j,nSlice,nFrame);
										roi_manager.addRoi(spotROI);
										nColocROIadded[(int)dIntermediate1[9]-1]=true;
									}
								}
								SpotsPositions.add(spotROI);
							}
							nColocCount++;
							
						}
					}
				
					cd.colocstat[nSlice-1][nFrame-1] = nColocCount;
				}//for(nSlice=1;
				
			}//for(nFrame=1

			//drawing the rest
			for(nCount = 0; nCount<nPatNumber; nCount++)
			{
				if(!colocalizations[nCount])
				{
					if( !cddlg.bPlotBothChannels)
					{
						
						spotROI = new Roi(xmin[nCount],ymin[nCount],xmax[nCount]-xmin[nCount],ymax[nCount]-ymin[nCount]);
						if((int)(channel[nCount])==1)
							spotROI.setStrokeColor(colorCh1);
						else
							spotROI.setStrokeColor(colorCh2);
						spotROI.setName(String.format("d%d_ch%d_sl%d_fr%d_c0", nCount+1,(int)channel[nCount],(int)slices[nCount],(int)frames[nCount]));
						SpotsPositions.add(spotROI);
						spotROI.setPosition((int)channel[nCount],(int)slices[nCount],(int)frames[nCount]);
						SpotsPositions.add(spotROI);
						if(cddlg.nRoiManagerAdd==1)
						{
							imp.setPosition((int)channel[nCount],(int)slices[nCount],(int)frames[nCount]);
							roi_manager.addRoi(spotROI);
						}
					}
					else
					{
						for (int k=1;k<3;k++)
						{

							spotROI = new Roi(xmin[nCount],ymin[nCount],xmax[nCount]-xmin[nCount],ymax[nCount]-ymin[nCount]);
							
							if((int)(channel[nCount])==1)
								spotROI.setStrokeColor(colorCh1);
							else
								spotROI.setStrokeColor(colorCh2);
							spotROI.setName(String.format("d%d_ch%d_sl%d_fr%d_c0_%d", nCount+1,(int)channel[nCount],(int)slices[nCount],(int)frames[nCount],k));
							spotROI.setPosition(k,(int)slices[nCount],(int)frames[nCount]);
							SpotsPositions.add(spotROI);
							if(cddlg.nRoiManagerAdd==1 && k==channel[nCount])
							{
								imp.setPosition(k,(int)slices[nCount],(int)frames[nCount]);
								roi_manager.addRoi(spotROI);
							}

						}
						
					}
				}				
			}
			//adding info about colocalization to Results table
			for(nCount = 0; nCount<nPatNumber; nCount++)
			{
				if(colocalizations[nCount])
				{
					cd.ptable.setValue("Colocalized", nCount,1);
					cd.ptable.setValue("IntensityRatio", nCount,colocIntRatio[nCount]);
					cd.ptable.setValue("ColocIndex", nCount,nColocFriend[nCount]);
					//check if we already added ROI to ROI manager
					if(!nColocROIadded[nCount] && cddlg.nRoiManagerAdd>0)
					{
						spotROI = new Roi(dColocRoiXYArray[nCount][0],dColocRoiXYArray[nCount][1],dColocRoiXYArray[nCount][2],dColocRoiXYArray[nCount][3]);
						spotROI.setStrokeColor(colorColoc);
						spotROI.setPosition((int)channel[nCount],(int)slices[nCount],(int)frames[nCount]);
						spotROI.setName(String.format("d%d_ch%d_sl%d_fr%d_c1", nCount+1,(int)channel[nCount],(int)slices[nCount],(int)frames[nCount]));
						imp.setPosition((int)channel[nCount],(int)slices[nCount],(int)frames[nCount]);
						roi_manager.addRoi(spotROI);
					}
				}
				else
				{
					cd.ptable.setValue("Colocalized", nCount,0);
					cd.ptable.setValue("IntensityRatio", nCount,0);
					cd.ptable.setValue("ColocIndex", nCount,0);
				}
			}
		}//if(!cddlg.bColocalization)
	
	}
	
	/**show summary and results tables**/
	void showTable()
	{
			String sSumData ="";
			String sColHeadings ="";
			int nChannel1Pat, nChannel2Pat, nColocNumber;
						
	        if(!cddlg.bColocalization)
	        {
	        	sColHeadings = "Channel\tSlice\tFrame\tNumber_of_Particles";
	        	for(int i = 0;i<cd.nParticlesCount.length; i++)
	        	{
	        		nCurrPos = imp.convertIndexToPosition(i+1);
	        		
	        		sSumData = sSumData +  Integer.toString(nCurrPos[0])+"\t" +Integer.toString(nCurrPos[1])+"\t" +Integer.toString(nCurrPos[2])+"\t" +Integer.toString(cd.nParticlesCount[i])+"\n";;
	         	//	sSumData = sSumData + Integer.toString(i+1)+"\t"+Integer.toString(cd.nParticlesCount[i])+"\n";
	         	
	        		//sSumData = sSumData + 
	        	}
			
	        	Frame frame = WindowManager.getFrame("Summary");
	        	if (frame!=null && (frame instanceof TextWindow) )
	        	{
	        		SummaryTable = (TextWindow)frame;
	        		SummaryTable.getTextPanel().clear();
	        		SummaryTable.getTextPanel().setColumnHeadings(sColHeadings);
	        		SummaryTable.getTextPanel().append(sSumData);
	        		SummaryTable.getTextPanel().updateDisplay();			
	        	}
	        	else
	        		SummaryTable = new TextWindow("Summary",sColHeadings , sSumData, 450, 300);
	        }
	        else
	        {
	        	sColHeadings = "Slice\tFrame\tParticles_in_Ch1\tParticles_in_Ch2\tColocalized\t%_Ch1_coloc\t%Ch2_coloc\t";
	        	for(int i = 1;i<=imageinfo[3]; i++)//slice
	        	{
		        	for(int j = 1;j<=imageinfo[4]; j++)//frame
		        	{

		        		nChannel1Pat = cd.nParticlesCount[imp.getStackIndex(1,i,j)-1];
		        		nChannel2Pat = cd.nParticlesCount[imp.getStackIndex(2,i,j)-1];
		        		nColocNumber = cd.colocstat[i-1][j-1];
		        		sSumData = sSumData +  Integer.toString(i)+"\t" +Integer.toString(j)+"\t";// +Integer.toString(nCurrPos[2])+"\t" +Integer.toString(cd.nParticlesCount[i])+"\n";;
	        		
		        		sSumData = sSumData + Integer.toString(nChannel1Pat)+"\t"+Integer.toString(nChannel2Pat)+"\t" + Integer.toString(nColocNumber)+"\t";
	        		
		        		sSumData = sSumData + String.format("%.2f", (100*(double)(nColocNumber)/(double)(nChannel1Pat)))+"\t"+String.format("%.2f", 100*(double)(nColocNumber)/(double)(nChannel2Pat))+"\n";
	         	
		        	} 
	        	}
			
	        	Frame frame = WindowManager.getFrame("Summary");
	        	if (frame!=null && (frame instanceof TextWindow) )
	        	{	        		
	        		SummaryTable = (TextWindow)frame;	        		
	        		SummaryTable.getTextPanel().clear();
	        		SummaryTable.getTextPanel().setColumnHeadings(sColHeadings);
	        		SummaryTable.getTextPanel().append(sSumData);
	        		SummaryTable.getTextPanel().updateDisplay();		

	        	}
	        	else
	        		SummaryTable = new TextWindow("Summary",sColHeadings , sSumData, 450, 300);
	        }
			
	        //Show Results table with coordinates
	            
			cd.ptable.show("Results");

	}
	/** Detection in one channel case (quantification in another) +  
	 * and adding them to overlay in case of single or simultaneous detection **/	
	void addParticlesToOverlayandQuantify()
	{
		
		int nCount;
		int nPatNumber;
		
		Roi spotROI;
		
		double [] absframe;
		double [] x;
		double [] y;
		double [] xmin;
		double [] ymin;
		double [] xmax;
		double [] ymax;
		double [] dInt;
		double [] frames;
		double [] channel;
		double [] slices;
		double [] narea;
		double []temp1;
		double []temp2;
		int nFrame;
		int nSlice;
		
		int nRefChNum;
		int nFolChNum;
		int i,j;
		String [] ColTitles = new String [] {"Abs_frame","X_(px)","Y_(px)","Channel","Slice","Frame","xMin","yMin","xMax","yMax","NArea","IntegratedInt"};
		ArrayList<double[]> spotsChRef = new ArrayList<double[]>();
		ArrayList<double[]> spotsChFol;
		
		int nTableCount=0;

		
		//coordinates
		x   = cd.ptable.getColumnAsDoubles(1);		
		y   = cd.ptable.getColumnAsDoubles(2);
		
		//
		xmin   = cd.ptable.getColumnAsDoubles(6);		
		ymin   = cd.ptable.getColumnAsDoubles(7);
		xmax   = cd.ptable.getColumnAsDoubles(8);		
		ymax   = cd.ptable.getColumnAsDoubles(9);
		narea  = cd.ptable.getColumnAsDoubles(10);
		dInt   = cd.ptable.getColumnAsDoubles(11);
		//absolute total number
		absframe = cd.ptable.getColumnAsDoubles(0);
		//channel
		channel   = cd.ptable.getColumnAsDoubles(3);
		//slice
		slices   = cd.ptable.getColumnAsDoubles(4);
		//frame number
		frames   = cd.ptable.getColumnAsDoubles(5);		
		nPatNumber = x.length;
		
		//filling up arrays with current frame
		nRefChNum= cddlg.nTwoChannelOption;
		if(nRefChNum==1)
			{nFolChNum=2;}
		else
			{nFolChNum=1;}
		cd.ptable.reset();
		for(nFrame=1; nFrame<=imageinfo[4]; nFrame++)
		{
			for(nSlice=1; nSlice<=imageinfo[3]; nSlice++)
			{
				//filling up arrays with current detections in both channels
				spotsChRef.clear();
				//spotsCh2.clear();
				for(nCount = 0; nCount<nPatNumber; nCount++)
				{
					if(frames[nCount]==nFrame && slices[nCount]==nSlice)
					{
						if(channel[nCount]==nRefChNum)
							spotsChRef.add(new double [] {absframe[nCount], x[nCount],y[nCount],nRefChNum,nSlice,nFrame, xmin[nCount],ymin[nCount],xmax[nCount],ymax[nCount], narea[nCount],dInt[nCount]});
					//	else
							//spotsCh2.add(new double [] {x[nCount],y[nCount], nCount, 0, xmin[nCount],xmax[nCount],ymin[nCount],ymax[nCount], dInt[nCount]});
					}
				}
				
				spotsChFol = correspChannelIntensities(spotsChRef, nFolChNum, nFrame, nSlice);
			
				for(i=0;i<spotsChRef.size();i++)
				{
					cd.ptable.incrementCounter();
					nTableCount++;
					temp1 = spotsChRef.get(i);
					temp2 = spotsChFol.get(i);
					for(j=0;j<12;j++)
					{
						cd.ptable.addValue(ColTitles[j],temp1[j]);
					}
					cd.ptable.addValue("Colocalized",1);
					cd.ptable.addValue("IntensityRatio", temp1[11]/temp2[11]);
					
					cd.ptable.incrementCounter();
					
					for(j=0;j<12;j++)
					{
						cd.ptable.addValue(ColTitles[j],temp2[j]);
					}
					cd.ptable.addValue("Colocalized",1);
					cd.ptable.addValue("IntensityRatio", temp2[11]/temp1[11]);
					
					spotROI = new Roi(temp1[6],temp1[7],temp1[8]-temp1[6],temp1[9]-temp1[7]);
					if(nRefChNum==1)
						spotROI.setStrokeColor(colorCh1);
					else
						spotROI.setStrokeColor(colorCh2);
					spotROI.setPosition(nRefChNum,(int)temp1[4],(int)temp1[5]);
					//spotROI.setPosition((int)temp1[0]);
					SpotsPositions.add(spotROI);	
					if(cddlg.nRoiManagerAdd>0)
					{
						spotROI.setName(String.format("d%d_ch%d_sl%d_fr%d_c1", (int)(2*nTableCount-1),nRefChNum,(int)temp1[4],(int)temp1[5]));
						imp.setPosition(nRefChNum,(int)temp1[4],(int)temp1[5]);
						roi_manager.addRoi(spotROI);
					}
				}
				//extra cycle to add more stuff to ROI Manager
				int lastFrameN=spotsChRef.size();
				for(i=0;i<spotsChRef.size();i++)
				{
					temp1 = spotsChRef.get(i);
					spotROI = new Roi(temp1[6],temp1[7],temp1[8]-temp1[6],temp1[9]-temp1[7]);
					if(nRefChNum==1)
						spotROI.setStrokeColor(colorCh1);
					else
						spotROI.setStrokeColor(colorCh2);
					spotROI.setPosition(nFolChNum,(int)temp1[4],(int)temp1[5]);
					if(cddlg.nRoiManagerAdd>0)
					{
						int index=2*(nTableCount-lastFrameN)+(i+1)*2;
						spotROI.setName(String.format("d%d_ch%d_sl%d_fr%d_c1", index,nFolChNum,(int)temp1[4],(int)temp1[5]));
						imp.setPosition(nFolChNum,(int)temp1[4],(int)temp1[5]);
						roi_manager.addRoi(spotROI);
					}
				}
			}//for(nFrame=1; nFrame<=imageinfo[4]; nFrame++)						
		}//for(nFrame=1; nFrame<=imageinfo[4]; nFrame++)
		
	}// addParticlesToOverlayandQuantify
	
	
	ArrayList<double[]> correspChannelIntensities(ArrayList<double[]> spotsChRefX, int nFolChNum, int nFrame, int nSlice)	
	{
		ArrayList<double[]> spotsChFol;
		ImageProcessor ip;
		int i;
		int nAbsFrame;
		double [] tempval;
		spotsChFol = new ArrayList<double[]>();
		imp.setPositionWithoutUpdate(nFolChNum, nSlice, nFrame);
		nAbsFrame=imp.getStackIndex(nFolChNum, nSlice, nFrame);
		ip = imp.getProcessor();
		for(i=0;i<spotsChRefX.size();i++)
		{
			tempval=new double [12];
			System.arraycopy(spotsChRefX.get(i),0,tempval,0,11);
			tempval[0]=nAbsFrame;
			tempval[3]=nFolChNum;
			tempval[11]=CDAnalysis.getIntIntNoise(ip, (int)tempval[6], (int)tempval[8], (int)tempval[7], (int)tempval[9]);
			spotsChFol.add(tempval);
		}
		return spotsChFol;		
	}

}

class CDThread extends Thread 
{

	private ImageProcessor ip;
	private CDDialog cddlg;
	private int [] nImagePos;
	private int nSlice;
	private CDAnalysis cd;
	private Roi RoiActive;
	private CDProgressCount cdcount;
	private int nStackSize;
	
	public void cdsetup(ImageProcessor ip, CDAnalysis cd, CDDialog cddlg, int [] nImagePos, int nSlice, Roi RoiActive,  int nStackSize, CDProgressCount cdcount)
	{
		this.cd     = cd;
		this.ip     = ip;
		this.cddlg  = cddlg;
		this.nImagePos = nImagePos;
		this.nSlice = nSlice;
		this.RoiActive = RoiActive;
		this.cdcount = cdcount;
		this.nStackSize = nStackSize;		
	}
	
	public void run()
	{
		this.cd.detectParticles(this.ip, this.cddlg, this.nImagePos, this.nSlice, this.RoiActive);
		cdcount.CDProgressCountIncrease();
		IJ.showProgress(cdcount.nSliceLeft-1, nStackSize);
	}
}



class CDProgressCount
{
	 
	public int nSliceLeft;
	public CDProgressCount (int ini_value)
	{
		nSliceLeft = ini_value;
	}
	public void CDProgressCountIncrease()
	{
		synchronized (this)
		{ 
			nSliceLeft++;
		}
		
	}
	public void CDProgressCountIncreaseValue(int inc_value)
	{
		synchronized (this)
		{ 
			nSliceLeft+=inc_value;
		}
		
	}
	
}