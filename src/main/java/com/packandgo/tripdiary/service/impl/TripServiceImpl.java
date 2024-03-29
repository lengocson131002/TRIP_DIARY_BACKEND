package com.packandgo.tripdiary.service.impl;

import com.packandgo.tripdiary.enums.NotificationType;

import com.packandgo.tripdiary.exception.TripNotFoundException;

import com.packandgo.tripdiary.model.Notification;
import com.packandgo.tripdiary.model.Trip;
import com.packandgo.tripdiary.model.User;
import com.packandgo.tripdiary.payload.request.trip.TripRequest;
import com.packandgo.tripdiary.repository.*;

import com.packandgo.tripdiary.model.mail.InviteJoinTripContent;
import com.packandgo.tripdiary.model.mail.MailContent;
import com.packandgo.tripdiary.repository.DestinationRepository;
import com.packandgo.tripdiary.repository.TripRepository;
import com.packandgo.tripdiary.repository.UserRepository;

import com.packandgo.tripdiary.service.EmailSenderService;

import com.packandgo.tripdiary.service.TripService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import javax.transaction.Transactional;
import java.util.ArrayList;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;


@Service
public class TripServiceImpl implements TripService {
    private final TripRepository tripRepository;
    private final UserRepository userRepository;
    private final DestinationRepository destinationRepository;
    private final EmailSenderService mailService;
    private final NotificationRepository notificationRepository;


    @Value("${tripdiary.baseurl.frontend}")
    private String frontendUrl;

    @Autowired
    public TripServiceImpl(TripRepository tripRepository,
                           UserRepository userRepository,
                           DestinationRepository destinationRepository,
                           LikeRepository likeRepository,
                           EmailSenderService mailService,
                           NotificationRepository notificationRepository) {
        this.tripRepository = tripRepository;
        this.userRepository = userRepository;
        this.destinationRepository = destinationRepository;
        this.mailService = mailService;
        this.notificationRepository = notificationRepository;
    }

    @Override
    @Transactional
    public Trip insertTrip(TripRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Trip information is required");
        }

        //get current user
        UserDetails userDetails = (UserDetails) SecurityContextHolder
                .getContext()
                .getAuthentication()
                .getPrincipal();

        User user = userRepository.findByUsername(userDetails.getUsername()).orElseThrow(
                () -> new UsernameNotFoundException("Unauthorized user")
        );


        if (request.getName() == null || request.getName().trim().length() == 0) {
            throw new IllegalArgumentException("Trip's name is required");
        }
        if (request.getDestination() == null) {
            throw new IllegalArgumentException("Trip's destination is required");
        }
        if(request.getEndDate() != null && request.getEndDate().before(request.getBeginDate())) {
            throw new IllegalArgumentException("Trip's end date must not be before trip's start day");
        }
        if (request.getNotifyBefore() < 1) {
            throw new IllegalArgumentException("Trip should be announced at least 1 day earlier than its starting");
        }



        Trip newTrip = new Trip();
        newTrip.mapping(request);
        newTrip.setOwner(user.getUsername());
        newTrip.addUser(user);
        Trip savedTrip = tripRepository.save(newTrip);
        return savedTrip;
    }

    @Override
    public Page<Trip> getTrips(int page, int size) {
        Pageable paging = PageRequest.of(page - 1, size);
        Page<Trip> trips = tripRepository.findAll(paging);

        return trips;
    }

    @Override
    public Page<Trip> getTripsAdmin(int page, int size) {
        Pageable paging = PageRequest.of(page - 1, size);
        Page<Trip> trips = tripRepository.findAllAdmin(paging);

        return trips;
    }

    @Override
    @Transactional
    public Trip removeTrip(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Removed trip's ID is required");
        }

        UserDetails userDetails = (UserDetails) SecurityContextHolder
                .getContext()
                .getAuthentication()
                .getPrincipal();

        User user = userRepository.findByUsername(userDetails.getUsername()).get();

        Trip existedTrip = null;

        if(user.getRoles().stream().filter(role -> "ADMIN".equals(role.getName())).collect(Collectors.toList()).size() > 0) {
            existedTrip = tripRepository.findById(id).orElseThrow(() ->
                    new TripNotFoundException("Trip not found")
            );
        } else {
            List<Trip> trips = userRepository.findsTripByUserId(user.getId());

            existedTrip = trips.stream().filter(trip -> trip.getId() == id).findAny().orElseThrow(
                    () -> new IllegalArgumentException("You have no permission to delete this trip")
            );
        }

        try {
            tripRepository.delete(existedTrip);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return existedTrip;
    }

    @Override
    @Transactional
    public Trip updateTrip(Long tripId, TripRequest request) {

        UserDetails userDetails = (UserDetails) SecurityContextHolder
                .getContext()
                .getAuthentication()
                .getPrincipal();

        User user = userRepository.findByUsername(userDetails.getUsername()).get();
        List<Trip> trips = userRepository.findsTripByUserId(user.getId());

        Trip trip = trips.stream().filter(t -> t.getId() == tripId).findAny().orElseThrow(
                () -> new IllegalArgumentException("You have no permission to update this trip")
        );

        if (request.getName() == null || request.getName().trim().length() == 0) {
            throw new IllegalArgumentException("Trip's name is required");
        }
        if(request.getEndDate() != null && request.getEndDate().before(request.getBeginDate())) {
            throw new IllegalArgumentException("Trip's end day must not be before trip's start day");
        }
        if (request.getDestination() == null) {
            throw new IllegalArgumentException("Trip's destination is required");
        }
        if (request.getNotifyBefore() < 1) {
            throw new IllegalArgumentException("Trip should be announced at least 1 day earlier than its starting");
        }

        //remove the old destination in database
        if (trip.getDestination() != null) {
            destinationRepository.delete(trip.getDestination());
        }

        trip.mapping(request);

        Trip savedTrip = tripRepository.save(trip);
        return savedTrip;
    }

    @Override
    public Trip get(Long id) {
        Trip trip = tripRepository.findById(id).orElseThrow(
                () -> new IllegalArgumentException("Trip with ID \"" + id + "\" doesn't exist")
        );
        return trip;
    }

    @Override
    public boolean existedTrip(Long tripId) {
        return tripRepository.existsById(tripId);
    }

    @Override
    public List<Trip> getNotifiedTripsForDay() {
        return tripRepository.getTripsForToday();
    }



    public void inviteToJoinTrip(Long tripId, String username) {
        UserDetails userDetails = (UserDetails) SecurityContextHolder
                .getContext()
                .getAuthentication()
                .getPrincipal();

        User user = userRepository.findByUsername(userDetails.getUsername()).get();
        List<Trip> trips = userRepository.findsTripByUserId(user.getId());

        //check whether the user can access this trip
        trips.stream().filter(t -> t.getId() == tripId).findAny().orElseThrow(
                () -> new IllegalArgumentException("You have no permission to invite trip mate for this trip")
        );

        //check whether this trip with this trip is exists or not
        Trip existedTrip = tripRepository.findById(tripId).orElseThrow(
                () -> new IllegalArgumentException("Trip with ID \"" + tripId + "\" doesn't exist")
        );

        //check whether username exist or not
        User invitedUser = userRepository.findByUsernameOrEmail(username, username).orElseThrow(
                () -> new IllegalArgumentException("User with username or email \"" + username + "\" doesn't exist")
        );

        if (invitedUser.getUsername().equals(existedTrip.getOwner())) {
            throw new IllegalArgumentException(username + " is this trip's owner");
        }

        if (hasUser(existedTrip, invitedUser)) {
            throw new IllegalArgumentException(username + " was invited to join this trip before");
        }

        MailContent invitationMail = new InviteJoinTripContent(existedTrip, invitedUser, frontendUrl);
        mailService.sendEmail(invitationMail);
        existedTrip.addUser(invitedUser);

        //create notification
        Notification notification = new Notification();
        notification.setTrip(existedTrip);
        notification.setCreatedAt(new Date());
        notification.setUser(invitedUser);
        notification.setType(NotificationType.INVITATION);
        notificationRepository.save(notification);

        tripRepository.save(existedTrip);

    }

    @Override
    @Transactional
    public void removeTripMate(Long tripId, String username) {
        UserDetails userDetails = (UserDetails) SecurityContextHolder
                .getContext()
                .getAuthentication()
                .getPrincipal();

        User user = userRepository.findByUsername(userDetails.getUsername()).get();
        List<Trip> trips = userRepository.findsTripByUserId(user.getId());

        //check whether the user can access this trip
        trips.stream().filter(t -> t.getId() == tripId).findAny().orElseThrow(
                () -> new IllegalArgumentException("You have no permission to invite trip mate for this trip")
        );

        //check whether this trip with this trip is exists or not
        Trip existedTrip = tripRepository.findById(tripId).orElseThrow(
                () -> new IllegalArgumentException("Trip with ID \"" + tripId + "\" doesn't exist")
        );

        //check whether username exist or not
        User invitedUser = userRepository.findByUsernameOrEmail(username, username).orElseThrow(
                () -> new IllegalArgumentException("User with username or email \"" + username + "\" doesn't exist")
        );

        if (existedTrip.getOwner().equals(invitedUser.getUsername())) {
            throw new IllegalArgumentException(username + " is this trip's owner");
        }


        if (!hasUser(existedTrip, invitedUser)) {
            throw new IllegalArgumentException(username + " was not invited to join this trip before");
        }

        existedTrip.removeUser(invitedUser);
        tripRepository.save(existedTrip);
    }

    @Override
    public List<Trip> search(String keyword) {
        List<Trip> trips = new ArrayList<>();
        if(keyword == null || "".equals(keyword)) {
            return  trips;
        }

        return tripRepository.search("%" + keyword.toLowerCase()+ "%");
    }

    private boolean hasUser(Trip trip, User user) {
        for (User u : trip.getUsers()) {
            if (u.getUsername().equals(user.getUsername())) {
                return true;
            }
        }
        return false;
    }
}
